package com.bss.insight.listen;

import com.bss.insight.security.TenantContext;
import com.bss.insight.service.ProspectService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Inbound leads become nurture prospects. When Sales captures a lead — a social
 * lead-form entry, an inbound enquiry — the martech side turns it into a
 * reachable prospect automatically, so "social lead → welcome journey" needs no
 * hand-off. Consent rides along: a captured first-party lead is reachable with a
 * recorded basis (a bought list still is not — that path stays gated).
 */
@Component
public class ProspectLeadListener {

    private static final Logger log = LoggerFactory.getLogger(ProspectLeadListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() { };

    private final ProspectService prospects;
    private final ObjectMapper objectMapper;

    public ProspectLeadListener(ProspectService prospects, ObjectMapper objectMapper) {
        this.prospects = prospects;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.insight.lead-topics:bss.quote.events}'.split(',')}", groupId = "insight-leads")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            if (!"SalesLeadCreateEvent".equals(String.valueOf(envelope.get("eventType")))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            if (!(event.get("salesLead") instanceof Map<?, ?> leadRaw)) {
                return;
            }
            Map<String, Object> lead = (Map<String, Object>) leadRaw;
            String email = lead.get("contactEmail") == null ? null : String.valueOf(lead.get("contactEmail"));
            if (email == null) {
                return; // a lead with no address is not reachable on owned channels
            }
            String name = lead.get("contactName") == null ? null : String.valueOf(lead.get("contactName"));
            String source = lead.get("source") == null ? "lead" : String.valueOf(lead.get("source"));
            String basis = "social".equalsIgnoreCase(source) ? "social-lead-form" : "inbound-lead";
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                prospects.captureLead(email, name, source, basis);
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable sales-lead event: {}", e.getMessage());
        }
    }
}
