package com.bss.intelligence.service;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.llm.LlmAdapter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Next best offer, honestly assembled: the TMF680 ranking (already fused
 * with consented interests) supplies the candidates, the customer's
 * holdings and interests supply the context, and the model's only job is
 * the REASON — one offer, one why, in words an agent can say out loud.
 * The model never invents candidates: no ranking, no answer.
 */
@Service
public class NextBestOfferService {

    private final BssApiClient bss;
    private final LlmAdapter llm;

    private final com.bss.intelligence.llm.AiGovernor governor;

    public NextBestOfferService(BssApiClient bss, LlmAdapter llm,
            com.bss.intelligence.llm.AiGovernor governor) {
        this.bss = bss;
        this.llm = llm;
        this.governor = governor;
    }

    public NextBestOffer nextBestOffer(String partyId) {
        List<Map<String, Object>> candidates = bss.recommendationItems(partyId);
        if (candidates.isEmpty()) {
            return new NextBestOffer(null, "No candidates: the customer either owns the whole shelf or the"
                    + " recommendation component is not deployed.", null, null, null);
        }
        List<String> interests = bss.interestsOf(partyId);
        List<String> holdings = bss.holdingsOf(partyId).stream()
                .map(p -> String.valueOf(p.get("name")))
                .limit(10)
                .toList();
        StringBuilder user = new StringBuilder();
        for (Map<String, Object> item : candidates) {
            if (item.get("offering") instanceof Map<?, ?> off) {
                user.append("CANDIDATE: ").append(off.get("name"))
                        .append(" | ").append(off.get("id")).append('\n');
            }
        }
        holdings.forEach(h -> user.append("HOLDING: ").append(h).append('\n'));
        interests.forEach(i -> user.append("INTEREST: ").append(i).append('\n'));
        String system = "You are a next best offer adviser for a telecom CSR. From the CANDIDATE"
                + " list (already ranked, best first) pick ONE offer and explain WHY in one or two"
                + " short sentences an agent can say to the customer — grounded in the HOLDING and"
                + " INTEREST lines only. Answer as JSON: {\"offerName\": ..., \"offerId\": ...,"
                + " \"reason\": ...}. Never invent an offer that is not a CANDIDATE.";
        String answer = governor.complete("next-best-offer",
                com.bss.intelligence.llm.LlmAdapter.Tier.SMART, system, user.toString());
        Map<String, Object> parsed = parse(answer);
        OfferRef first = candidates.get(0).get("offering") instanceof Map<?, ?> off
                ? new OfferRef(String.valueOf(off.get("id")), String.valueOf(off.get("name")))
                : OfferRef.NONE;
        OfferRef offer;
        String reason;
        if (parsed != null && parsed.get("offerName") != null) {
            offer = new OfferRef(String.valueOf(parsed.getOrDefault("offerId", first.id())),
                    String.valueOf(parsed.get("offerName")));
            reason = String.valueOf(parsed.getOrDefault("reason", ""));
        } else {
            // the model misbehaved: the ranking still stands on its own
            offer = first;
            reason = "Top of the ranking for this customer.";
        }
        return new NextBestOffer(offer, reason, interests, llm.provider(), llm.model());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String answer) {
        try {
            String json = answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1);
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
