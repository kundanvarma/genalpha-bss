package com.bss.ticket.listen;

import com.bss.ticket.repository.TroubleTicketRepository;
import com.bss.ticket.security.TenantContext;
import com.bss.ticket.service.TroubleTicketService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Social care closes the loop: insight triages inbound social DMs and, when one
 * needs a human, emits SocialCareTicketRequested on the bus. This listener turns
 * that request into a TMF621 trouble ticket — the CDP never calls the ticket
 * service (no cross-service write scope on the hot path); the operational bus
 * carries the intent and the ticket service owns the case. Idempotent per source
 * DM, so an at-least-once re-delivery never opens a duplicate.
 */
@Component
public class SocialCareTicketListener {

    private static final Logger log = LoggerFactory.getLogger(SocialCareTicketListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() { };

    private final TroubleTicketService tickets;
    private final TroubleTicketRepository repository;
    private final ObjectMapper objectMapper;

    public SocialCareTicketListener(TroubleTicketService tickets, TroubleTicketRepository repository,
            ObjectMapper objectMapper) {
        this.tickets = tickets;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.social-care.source-topic:bss.insight.events}'.split(',')}",
            groupId = "trouble-ticket-social-care")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            if (!"SocialCareTicketRequested".equals(String.valueOf(envelope.get("eventType")))) {
                return; // this topic carries every insight event; we only want care requests
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Map<String, Object> req = resourceOf(event);
            if (req == null) {
                return;
            }
            String sourceId = str(req.get("sourceId"));
            if (sourceId == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                if (repository.existsByTenantIdAndRelatedEntityJsonContaining(tenantId, sourceId)) {
                    return; // already opened a ticket for this DM — at-least-once dedup
                }
                String handle = str(req.get("handle"));
                String author = str(req.get("author"));
                String who = handle != null ? handle : (author != null ? author : "a social user");
                String text = str(req.get("text"));
                String sentiment = str(req.get("sentiment"));
                String reason = str(req.get("reason"));

                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("name", "Social care: " + who);
                dto.put("description", "Inbound " + str(req.get("platform")) + " message from " + who
                        + (text == null ? "" : ": “" + text + "”"));
                dto.put("ticketType", "socialCare");
                dto.put("severity", "negative-sentiment".equals(reason) ? "major" : "minor");
                // The DM is the ticket's source — carries the dedup key AND the
                // reply handle, so an agent can respond on the same channel.
                dto.put("relatedEntity", List.of(Map.of(
                        "id", sourceId,
                        "role", "source",
                        "@referredType", "SocialDm",
                        "name", who,
                        "channel", str(req.get("platform")) == null ? "social" : str(req.get("platform")),
                        "handle", handle == null ? "" : handle)));
                dto.put("note", List.of(Map.of("text",
                        "Opened from social care. Sentiment: " + sentiment + "; reason: " + reason + ".")));

                Map<String, Object> created = tickets.create(dto);
                log.info("opened social-care ticket {} for {} ({})", created.get("id"), sourceId, reason);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable social-care event: {}", e.getMessage());
        }
    }

    /** The single resource an event envelope carries (event = {resourceKey: resource}). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourceOf(Map<String, Object> event) {
        for (Object v : event.values()) {
            if (v instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
