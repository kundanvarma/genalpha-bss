package com.bss.intelligence.chat;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.bss.intelligence.chat.ChatViews.AgentReply;
import com.bss.intelligence.chat.ChatViews.ChatMessageView;
import com.bss.intelligence.chat.ChatViews.ChatSessionView;
import com.bss.intelligence.chat.ChatViews.ChatTurn;
import com.bss.intelligence.chat.ChatViews.EscalationReceipt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The customer-facing conversation rail. The governing idea: the bot is
 * SCOPE-BOUNDED BY CONSTRUCTION — for a signed-in customer it answers only
 * from data fetched for the token's own subject, so it structurally cannot
 * discuss anyone else's account; for a guest it answers only from the public
 * shelf. The bot proposes, informs and escalates — the moment a human agent
 * joins, the bot goes silent (humans outrank models).
 */
@Service
public class CareChatService {

    private static final int HISTORY = 12;

    private static final String SYSTEM_AUTHED = """
            You are the care assistant of a telecom operator, chatting with a signed-in \
            customer. You are given THIS CUSTOMER'S OWN account snapshot (bills, orders, \
            usage meters) and nothing else. Answer briefly and warmly (2-4 sentences), \
            concrete numbers first. Only use the snapshot — if it does not answer the \
            question, say so and offer a human follow-up. YOU CANNOT PERFORM ACTIONS \
            YOURSELF: if the customer wants a human or a ticket, reply with exactly \
            [ESCALATE] and nothing else — the system will raise the real ticket. \
            Never claim an action happened. Never invent amounts, dates or policies. \
            Never discuss any other customer.""";

    private static final String SYSTEM_GUEST = """
            You are the shop assistant of a telecom operator, chatting with a visitor who \
            is not signed in. You are given the public product shelf and nothing else. \
            Help them choose (2-4 sentences, name real plans and prices from the shelf). \
            For account-specific questions, ask them to sign in. YOU CANNOT PERFORM \
            ACTIONS YOURSELF: if the visitor wants a human or a ticket, reply with \
            exactly [ESCALATE] and nothing else — the system will raise the real \
            ticket. Never claim an action happened. Never invent plans or prices.""";

    private final CareChatSessionRepository sessions;
    private final CareChatMessageRepository messages;
    private final AiGovernor governor;
    private final BssApiClient bss;
    private final TenantScope tenantScope;

    public CareChatService(CareChatSessionRepository sessions, CareChatMessageRepository messages,
            AiGovernor governor, BssApiClient bss, TenantScope tenantScope) {
        this.sessions = sessions;
        this.messages = messages;
        this.governor = governor;
        this.bss = bss;
        this.tenantScope = tenantScope;
    }

    /* ---------------- sessions ---------------- */

    public CareChatSession open(String partyIdOrNull) {
        CareChatSession s = new CareChatSession();
        s.setTenantId(tenantScope.currentTenantId());
        s.setPartyId(partyIdOrNull);
        s.setChannel(partyIdOrNull == null ? "guest" : "authed");
        if (partyIdOrNull == null) {
            // warm the shelf while the visitor is still typing their first
            // message — the crawl must never sit inside a reply
            java.util.concurrent.CompletableFuture.runAsync(this::guestContext);
        }
        return sessions.save(s);
    }

    public CareChatSession require(String id, String partyIdOrNull, boolean asAgent) {
        CareChatSession s = sessions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "no such chat"));
        if (!s.getTenantId().equals(tenantScope.currentTenantId())) {
            throw new ResponseStatusException(NOT_FOUND, "no such chat");
        }
        if (!asAgent) {
            // guests hold the session id as their capability; authed customers
            // must BE the session's party — no parameter to probe
            if (s.getPartyId() != null && !s.getPartyId().equals(partyIdOrNull)) {
                throw new ResponseStatusException(FORBIDDEN, "not your conversation");
            }
        }
        return s;
    }

    public List<ChatMessageView> transcript(CareChatSession s) {
        List<ChatMessageView> out = new ArrayList<>();
        for (CareChatMessage m : messages.findBySessionIdOrderByCreatedAtAsc(s.getId())) {
            out.add(new ChatMessageView(m.getId(), m.getAuthor(), m.getBody(),
                    m.getCreatedAt().toString()));
        }
        return out;
    }

    public List<ChatSessionView> openSessions() {
        List<ChatSessionView> out = new ArrayList<>();
        for (CareChatSession s : sessions.findTop50ByTenantIdAndStatusInOrderByUpdatedAtDesc(
                tenantScope.currentTenantId(), List.of("open", "agent", "escalated"))) {
            List<CareChatMessage> t = messages.findBySessionIdOrderByCreatedAtAsc(s.getId());
            out.add(new ChatSessionView(s.getId(), s.getChannel(), s.getStatus(), s.getPartyId(),
                    s.getTicketId(), s.getUpdatedAt().toString(), t.size(),
                    t.isEmpty() ? "" : t.get(t.size() - 1).getBody()));
        }
        return out;
    }

    /* ---------------- the conversation ---------------- */

    public ChatTurn customerMessage(CareChatSession s, String text) {
        messages.save(new CareChatMessage(s.getId(), s.getTenantId(), "customer", clip(text, 4000)));
        s.touch();
        sessions.save(s);

        // an agent has taken over: the bot stays silent, the human replies
        if ("agent".equals(s.getStatus()) || "escalated".equals(s.getStatus())) {
            return new ChatTurn(null, s.getStatus(), null);
        }

        String context = s.getPartyId() == null ? guestContext() : accountContext(s.getPartyId());
        String prompt = "CONTEXT:\n" + context + "\n\nCONVERSATION SO FAR:\n" + history(s)
                + "\nCUSTOMER: " + clip(text, 1000) + "\nASSISTANT:";
        String reply = null;
        // provider capacity blips (529/429) are normal weather — retry with
        // backoff before ever apologising to a customer
        for (int attempt = 1; attempt <= 3 && reply == null; attempt++) {
            try {
                // pools have independent capacity: attempts 1-2 ride FAST,
                // attempt 3 jumps to SMART rather than queueing politely
                LlmAdapter.Tier tier = attempt < 3 ? LlmAdapter.Tier.FAST : LlmAdapter.Tier.SMART;
                reply = governor.complete("careChat", tier,
                        s.getPartyId() == null ? SYSTEM_GUEST : SYSTEM_AUTHED, prompt);
            } catch (Exception e) {
                String msg = e.toString();
                boolean transientErr = msg.contains("529") || msg.contains("429")
                        || msg.toLowerCase().contains("overloaded");
                org.slf4j.LoggerFactory.getLogger(CareChatService.class)
                        .warn("care-chat model call failed (attempt {}): {}", attempt, msg);
                if (!transientErr || attempt == 3) {
                    reply = "I'm having trouble answering right now. I can raise a ticket "
                            + "so a human follows up — just say the word.";
                } else {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        if (reply != null && reply.contains("[ESCALATE]")) {
            // the model may only SIGNAL; the system performs. A real ticket,
            // a real id, spoken truthfully — never a role-played action.
            EscalationReceipt esc = escalate(s, null);
            return new ChatTurn("I've raised ticket " + esc.ticketId()
                    + " — a human will pick this up shortly.", s.getStatus(), esc.ticketId());
        }
        messages.save(new CareChatMessage(s.getId(), s.getTenantId(), "bot", clip(reply, 4000)));
        s.touch();
        sessions.save(s);
        return new ChatTurn(reply, s.getStatus(), null);
    }

    public AgentReply agentMessage(CareChatSession s, String text) {
        messages.save(new CareChatMessage(s.getId(), s.getTenantId(), "agent", clip(text, 4000)));
        s.setStatus("agent");
        s.touch();
        sessions.save(s);
        return new AgentReply("agent");
    }

    public EscalationReceipt escalate(CareChatSession s, String contact) {
        StringBuilder transcript = new StringBuilder("Escalated from care chat ")
                .append(s.getId()).append(contact == null || contact.isBlank() ? "" : " (contact: " + contact + ")")
                .append("\n\n");
        for (CareChatMessage m : messages.findBySessionIdOrderByCreatedAtAsc(s.getId())) {
            transcript.append(m.getAuthor().toUpperCase()).append(": ").append(m.getBody()).append('\n');
        }
        String ticketId = bss.openTicket("Care chat escalation", clip(transcript.toString(), 8000),
                s.getPartyId());
        s.setStatus("escalated");
        s.setTicketId(ticketId);
        s.touch();
        sessions.save(s);
        messages.save(new CareChatMessage(s.getId(), s.getTenantId(), "bot",
                "I've raised ticket " + ticketId + " — a human will pick this up."));
        return new EscalationReceipt(ticketId, "escalated");
    }

    /* ---------------- grounding ---------------- */

    /** The signed-in customer's OWN snapshot — fetched for the token's
     * subject, never for a parameter. Small, fresh, and the only thing the
     * model ever sees about accounts. */
    private String accountContext(String partyId) {
        StringBuilder ctx = new StringBuilder();
        try {
            List<Map<String, Object>> bills = bss.billsOf(partyId);
            ctx.append("BILLS (newest first, up to 3):\n");
            for (int i = 0; i < Math.min(3, bills.size()); i++) {
                Map<String, Object> b = bills.get(i);
                ctx.append("- ").append(field(b, "billNo", "id")).append(": state=")
                        .append(b.get("state")).append(", amount=")
                        .append(String.valueOf(b.get("amountDue")).replace("null",
                                String.valueOf(b.get("taxIncludedAmount"))))
                        .append(", date=").append(field(b, "billDate", "billingPeriod")).append('\n');
            }
        } catch (Exception e) {
            ctx.append("BILLS: unavailable right now\n");
        }
        try {
            List<Map<String, Object>> meters = bss.usageMeters(partyId);
            ctx.append("USAGE METERS:\n");
            for (int i = 0; i < Math.min(4, meters.size()); i++) {
                Map<String, Object> m = meters.get(i);
                ctx.append("- ").append(m.get("name")).append(": ")
                        .append(m.get("bucketBalance") != null ? m.get("bucketBalance") : m)
                        .append('\n');
            }
        } catch (Exception e) {
            ctx.append("USAGE: unavailable right now\n");
        }
        try {
            List<Map<String, Object>> tickets = bss.ticketsOf(partyId);
            ctx.append("OPEN TICKETS: ").append(tickets.size()).append('\n');
        } catch (Exception e) { /* fine */ }
        return ctx.toString();
    }

    private volatile String shelfCache;
    private volatile long shelfCachedAt;

    /** The public shelf, compact: what a guest could see in the shop.
     * Cached for two minutes — the catalog crawl is paged and must never
     * run per-message. */
    private String guestContext() {
        if (shelfCache != null && System.currentTimeMillis() - shelfCachedAt < 120_000) {
            return shelfCache;
        }
        StringBuilder ctx = new StringBuilder("PUBLIC SHELF (name | monthly price):\n");
        try {
            List<Map<String, Object>> offs = bss.offeringsFirstPage();
            int shown = 0;
            for (Map<String, Object> o : offs) {
                if (!"Active".equals(o.get("lifecycleStatus"))
                        && !"Launched".equals(o.get("lifecycleStatus"))) {
                    continue;
                }
                ctx.append("- ").append(o.get("name"));
                Object desc = o.get("description");
                if (desc != null) {
                    ctx.append(" — ").append(clip(String.valueOf(desc), 90));
                }
                ctx.append('\n');
                if (++shown >= 12) {
                    break;
                }
            }
        } catch (Exception e) {
            ctx.append("(shelf unavailable)\n");
        }
        shelfCache = ctx.toString();
        shelfCachedAt = System.currentTimeMillis();
        return shelfCache;
    }

    private String history(CareChatSession s) {
        List<CareChatMessage> all = messages.findBySessionIdOrderByCreatedAtAsc(s.getId());
        StringBuilder h = new StringBuilder();
        for (int i = Math.max(0, all.size() - HISTORY); i < all.size(); i++) {
            CareChatMessage m = all.get(i);
            h.append(m.getAuthor().equals("customer") ? "CUSTOMER: " : "ASSISTANT: ")
                    .append(clip(m.getBody(), 500)).append('\n');
        }
        return h.toString();
    }

    private static String field(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.get(k) != null) {
                return String.valueOf(m.get(k));
            }
        }
        return "?";
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
