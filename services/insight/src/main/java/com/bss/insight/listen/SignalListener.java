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
import static com.bss.insight.api.Wire.idOf;
import static com.bss.insight.api.Wire.textOf;

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
            String tenantId = java.util.Objects.requireNonNullElse(textOf(envelope, "tenantId"), "genalpha");
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            Map<String, Object> ticket = event.get("troubleTicket") instanceof Map<?, ?> t
                    ? (Map<String, Object>) t : null;
            String ticketId = idOf(ticket);
            if (ticketId == null) {
                return;
            }
            String name = ticket.get("name") == null ? "" : String.valueOf(ticket.get("name"));
            String description = ticket.get("description") == null ? ""
                    : String.valueOf(ticket.get("description"));
            String text = (name + (description.isBlank() ? "" : " — " + description)).trim();
            if (text.isBlank()) {
                return;
            }
            String partyId = ticket.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                    ? idOf(parties.get(0)) : null;
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                com.fasterxml.jackson.databind.node.ObjectNode context = null;
                if (ticket.get("severity") != null) {
                    context = objectMapper.createObjectNode().put("severity", String.valueOf(ticket.get("severity")));
                }
                signals.ingest(new com.bss.insight.dto.SignalInput("ticket", text, ticketId,
                        partyId, "support", null, context));
            }
        } catch (Exception e) {
            log.warn("signal ingest from bus skipped: {}", e.getMessage());
        }
    }
}
