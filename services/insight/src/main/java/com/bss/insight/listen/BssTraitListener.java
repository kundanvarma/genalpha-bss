package com.bss.insight.listen;

import com.bss.insight.security.TenantContext;
import com.bss.insight.service.PartyTraitService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The BSS-native feature store's ingest: the operator's OWN domain events become
 * customer traits, in real time, with no export to a marketing tool. This is the
 * heart of "audiences from BSS data" — the operational bus IS the audience feed.
 * Start with product holdings from completed orders; every new trait is one more
 * listener branch, not a new pipeline.
 */
@Component
public class BssTraitListener {

    private static final Logger log = LoggerFactory.getLogger(BssTraitListener.class);
    private static final TypeReference<Map<String, Object>> JSON = new TypeReference<>() { };

    private final PartyTraitService traits;
    private final ObjectMapper objectMapper;

    public BssTraitListener(PartyTraitService traits, ObjectMapper objectMapper) {
        this.traits = traits;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.insight.trait-topics:bss.ordering.events}'.split(',')}",
            groupId = "insight-traits")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            String eventType = String.valueOf(envelope.get("eventType"));
            if (!"ProductOrderStateChangeEvent".equals(eventType)) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            if (!(event.get("productOrder") instanceof Map<?, ?> poRaw)) {
                return;
            }
            Map<String, Object> po = (Map<String, Object>) poRaw;
            if (!"completed".equals(String.valueOf(po.get("state")))) {
                return; // a product is HELD only once it is actually the customer's
            }
            String party = customerOf(po);
            List<String> products = productsOf(po);
            if (party == null || products.isEmpty()) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                for (String name : products) {
                    traits.upsert(party, "product", name);
                }
            }
        } catch (Exception e) {
            log.warn("skipping unprocessable order event for traits: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String customerOf(Map<String, Object> po) {
        if (po.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))
                        && ref.get("id") != null) {
                    return String.valueOf(ref.get("id"));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> productsOf(Map<String, Object> po) {
        if (!(po.get("productOrderItem") instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(i -> i instanceof Map<?, ?> item
                        && !"delete".equalsIgnoreCase(String.valueOf(item.get("action")))
                        && item.get("productOffering") instanceof Map<?, ?> po2 && po2.get("name") != null)
                .map(i -> String.valueOf(((Map<?, ?>) ((Map<?, ?>) i).get("productOffering")).get("name")))
                .distinct().toList();
    }
}
