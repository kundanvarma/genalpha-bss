package com.bss.ontology.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is asking: their bearer (forwarded downstream as-is), their subject, their
 * authorities, the sales channel they came through, and the tenant. A token that
 * carries the {@code customer} role is a customer — it may act on its own things
 * only, whatever else it holds.
 */
public record Caller(String bearer, String subject, Set<String> roles, String channel, String tenant, String agent,
        boolean agentVerified) {

    /** A caller whose agent, if any, only named itself (a header) — not proven by the token. */
    public Caller(String bearer, String subject, Set<String> roles, String channel, String tenant, String agent) {
        this(bearer, subject, roles, channel, tenant, agent, false);
    }

    public static final String CHANNEL_HEADER = "X-Channel";
    /** Which registered agent is acting for the person — named in every receipt. Absent = a person at a screen. */
    public static final String AGENT_HEADER = "X-GenAlpha-Agent";
    /**
     * The agent operator whose signed request the gateway verified (Web Bot Auth, #427).
     * Written only by the gateway's SignedAgentFilter, which removes any client copy.
     */
    public static final String VERIFIED_AGENT_HEADER = "X-Verified-Agent";

    public Caller withAgent(String name) {
        return new Caller(bearer, subject, roles, channel, tenant, name, false);
    }

    /**
     * How the agent is known, for the receipt (#422): {@code verified} — the token's
     * own {@code act} (actor) claim names it, issued by the identity server in a token
     * exchange, or the gateway verified the agent's request signature (#427);
     * {@code declared} — only the agent's own header says so; {@code none}.
     */
    public String agentEvidence() {
        return agent == null ? "none" : agentVerified ? "verified" : "declared";
    }

    public boolean isCustomer() {
        return roles.contains("customer");
    }

    public boolean has(String role) {
        return roles.contains(role);
    }

    public static Caller current(HttpServletRequest request, String tenant) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String bearer = "";
        String subject = auth == null ? "" : auth.getName();
        Set<String> roles = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        if (auth instanceof JwtAuthenticationToken jwt) {
            bearer = jwt.getToken().getTokenValue();
            subject = jwt.getToken().getSubject();
        }
        String channel = request == null ? null : request.getHeader(CHANNEL_HEADER);
        String agent = request == null ? null : request.getHeader(AGENT_HEADER);
        agent = agent == null || agent.isBlank() ? null : agent.trim();
        // An agent acting for a person through a token exchange (RFC 8693) is named by the
        // token's own act claim. That is proof; the header is only a claim, so when the token
        // names an actor, the token wins and the header cannot rename it (#422).
        String actor = auth instanceof JwtAuthenticationToken jwt ? actorOf(jwt) : null;
        if (actor == null && request != null) {
            // Next best: the gateway verified the agent's Web Bot Auth signature (#427) and
            // stamped the operator's name. The gateway strips this header from every inbound
            // request, so a client cannot set it; it outranks the self-declared header.
            String signed = request.getHeader(VERIFIED_AGENT_HEADER);
            actor = signed == null || signed.isBlank() ? null : signed.trim();
        }
        boolean verified = actor != null;
        return new Caller(bearer, subject, roles, channel == null || channel.isBlank() ? "web" : channel.trim(), tenant,
                verified ? actor : agent, verified);
    }

    /** The acting party in an RFC 8693 {@code act} claim — its client id or subject — or null. */
    static String actorOf(JwtAuthenticationToken jwt) {
        Object act = jwt.getToken().getClaims().get("act");
        if (act instanceof java.util.Map<?, ?> m) {
            Object who = m.get("client_id") != null ? m.get("client_id") : m.get("sub");
            if (who instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }
}
