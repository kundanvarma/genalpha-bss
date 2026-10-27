package com.bss.insight.listen;

import com.bss.insight.security.TenantContext;
import com.bss.insight.service.PartyTraitService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Benefit Choices become customer traits (#361). The benefit ledger's events
 * name the household and where its pool stands afterwards, so two facts are
 * kept without calling back into that component:
 * <ul>
 * <li>{@code choicesLeft} — a number, so "has unused Choices" is the audience
 * leaf {@code choicesLeft gte 1}. Events arrive in any order (the bus keys them
 * by event id), so the newest by event time wins and an older one is ignored;
 * {@code choicesAt} remembers which that was.</li>
 * <li>{@code benefit} — every benefit the household has active, by name:
 * added when picked, removed when released or swapped away. Which benefits
 * households choose is then an ordinary audience and an ordinary count.</li>
 * </ul>
 * A foreign BSS reaches the same traits through bss-bridge (BENEFIT_PICKED,
 * BENEFIT_ENDED), so an audience never asks which system ran the pick.
 */
@Component
public class ChoiceTraitListener {

    private static final Logger log = LoggerFactory.getLogger(ChoiceTraitListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() {
    };

    private final PartyTraitService traits;
    private final ObjectMapper objectMapper;

    public ChoiceTraitListener(PartyTraitService traits, ObjectMapper objectMapper) {
        this.traits = traits;
        this.objectMapper = objectMapper;
    }

    // the ledger's own topic, and the bridge's for an operator whose old BSS still runs the picks
    @KafkaListener(topics = "#{'${bss.insight.benefit-topics:bss.benefit.events,bss.bridge.events}'.split(',')}",
            groupId = "insight-choices")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            String tenantId = envelope.get("tenantId") instanceof String t && !t.isBlank() ? t : "genalpha";
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                apply(String.valueOf(envelope.get("eventType")), String.valueOf(envelope.get("eventTime")),
                        envelope.get("event") instanceof Map<?, ?> e ? e : Map.of());
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable benefit event for traits: {}", e.getMessage());
        }
    }

    /** One event's effect on the household's traits. Package-private for the unit test. */
    void apply(String eventType, String eventTime, Map<?, ?> event) {
        Map<?, ?> r = event.get("choiceAllocation") instanceof Map<?, ?> a ? a
                : event.get("choiceGrant") instanceof Map<?, ?> g ? g : null;
        String party = r == null ? null : customerOf(r.get("relatedParty"));
        if (party == null) {
            return;   // a cost row or an event that names no household: nothing to say about one
        }
        String name = r.get("offeringName") == null ? null : String.valueOf(r.get("offeringName"));
        switch (eventType) {
            case "ChoicePickedEvent" -> traits.upsert(party, "benefit", name);
            case "ChoiceReleasedEvent", "ChoiceSwappedEvent" -> traits.remove(party, "benefit", name);
            default -> {
                // held, granted, re-granted: only the count moves
            }
        }
        if (r.get("choicesLeft") instanceof Number left && newer(party, eventTime)) {
            traits.setTrait(party, "choicesLeft", String.valueOf(left.intValue()));
            traits.setTrait(party, "choicesAt", eventTime);
        }
    }

    private boolean newer(String party, String eventTime) {
        OffsetDateTime at = parse(eventTime);
        if (at == null) {
            return true;
        }
        OffsetDateTime seen = traits.valueOf(party, "choicesAt").map(ChoiceTraitListener::parse).orElse(null);
        return seen == null || !at.isBefore(seen);
    }

    private static OffsetDateTime parse(String at) {
        try {
            return at == null ? null : OffsetDateTime.parse(at);
        } catch (java.time.format.DateTimeParseException e) {
            return null;   // an unreadable time cannot win against a readable one
        }
    }

    private static String customerOf(Object related) {
        if (related instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> m && "customer".equals(m.get("role")) && m.get("id") instanceof String id
                        && !id.isBlank()) {
                    return id;
                }
            }
        }
        return null;
    }
}
