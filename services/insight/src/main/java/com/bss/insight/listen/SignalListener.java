package com.bss.insight.listen;

import com.bss.insight.security.TenantContext;
import com.bss.insight.service.SignalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Internal sources feed the signal store themselves (SI-P1): a trouble
 * ticket IS a customer signal — the customer told us something is wrong.
 * Same doctrine as the trait listener: the operational bus is the feed,
 * and every new internal source is one more branch, not a new pipeline.
 */
@Component
public class SignalListener {

    private static final Logger log = LoggerFactory.getLogger(SignalListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() { };

    private final SignalService signals;
    private final ObjectMapper objectMapper;

    public SignalListener(SignalService signals, ObjectMapper objectMapper) {
        this.signals = signals;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.insight.signal-topics:bss.ticket.events}'.split(',')}",
            groupId = "insight-signals")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            if (!"TroubleTicketCreateEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Map<String, Object> ticket = event.get("troubleTicket") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : null;
            if (ticket == null || ticket.get("id") == null) {
                return;
            }
            String name = ticket.get("name") == null ? "" : String.valueOf(ticket.get("name"));
            String description = ticket.get("description") == null ? ""
                    : String.valueOf(ticket.get("description"));
            String text = (name + (description.isBlank() ? "" : " — " + description)).trim();
            if (text.isBlank()) {
                return;
            }
            String partyId = null;
            if (ticket.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                    && parties.get(0) instanceof Map<?, ?> p && p.get("id") != null) {
                partyId = String.valueOf(p.get("id"));
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                Map<String, Object> dto = new java.util.LinkedHashMap<>();
                dto.put("source", "ticket");
                dto.put("sourceRef", String.valueOf(ticket.get("id")));
                dto.put("channel", "support");
                dto.put("text", text);
                if (partyId != null) {
                    dto.put("partyId", partyId);
                }
                if (ticket.get("severity") != null) {
                    dto.put("context", Map.of("severity", String.valueOf(ticket.get("severity"))));
                }
                signals.ingest(dto);
            }
        } catch (Exception e) {
            log.warn("signal ingest from bus skipped: {}", e.getMessage());
        }
    }
}
