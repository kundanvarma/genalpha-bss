package com.bss.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Normalize a foreign BSS's event to the martech envelope and publish it onto
 * the martech ingress topic. The per-BSS knowledge is a MAPPING (which foreign
 * field is the type, which fields carry the customer/product/email) — here it is
 * a small in-code table for the demo source; in production it is a mounted
 * JSON/YAML per source BSS, so onboarding a new BSS never touches the martech
 * services. Two transforms cover most acquisition-relevant events: a completed
 * order (→ product trait) and a customer create (→ email trait).
 */
@Service
public class BridgeService {

    private static final Logger log = LoggerFactory.getLogger(BridgeService.class);

    /** Per-source adapter config. Externalize to a mounted file per BSS in prod. */
    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "acme-bss", new Mapping("genalpha", "kind", Map.of(
                    "ORDER_COMPLETED", new EventMap("ProductOrderStateChangeEvent", "bss.bridge.events",
                            "productOrder", "order", Map.of("customerId", "account.ref", "items", "lines", "itemName", "sku")),
                    "CUSTOMER_CREATED", new EventMap("IndividualCreateEvent", "bss.bridge.events",
                            "individual", "individual", Map.of("id", "account.ref", "firstName", "account.firstName", "email", "account.mail")),
                    // single-valued BSS signals — relatedParty + one field, mapped generically
                    "LOYALTY_TIER", new EventMap("LoyaltyTierChangedEvent", "bss.bridge.events",
                            "loyaltyMember", "kv", Map.of("customerId", "account.ref", "tier", "tier")),
                    "CHURN_SCORED", new EventMap("ChurnRiskDetectedEvent", "bss.bridge.events",
                            "churnRisk", "kv", Map.of("customerId", "account.ref", "band", "band")),
                    "BILL_ISSUED", new EventMap("CustomerBillCreateEvent", "bss.bridge.events",
                            "customerBill", "kv", Map.of("customerId", "account.ref", "amount", "amount"))
            )));

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public BridgeService(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> ingest(String source, Map<String, Object> foreign) {
        Mapping mapping = MAPPINGS.get(source);
        if (mapping == null) {
            throw new IllegalArgumentException("no adapter config for source BSS '" + source + "'");
        }
        String foreignType = String.valueOf(path(foreign, mapping.typeField()));
        EventMap em = mapping.events().get(foreignType);
        if (em == null) {
            return Map.of("status", "ignored", "reason", "unmapped foreign event type '" + foreignType + "'");
        }
        Map<String, Object> resource = switch (em.transform()) {
            case "order" -> buildOrder(em, foreign);
            case "individual" -> buildIndividual(em, foreign);
            default -> buildKv(em, foreign); // relatedParty + one flat field
        };
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventTime", OffsetDateTime.now().toString());
        envelope.put("eventType", em.targetType());
        envelope.put("tenantId", mapping.tenantId());
        envelope.put("event", Map.of(em.resourceKey(), resource));
        try {
            kafka.send(em.topic(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish normalized event", e);
        }
        log.info("bridged {}:{} -> {} on {}", source, foreignType, em.targetType(), em.topic());
        return Map.of("status", "forwarded", "eventType", em.targetType(), "topic", em.topic(),
                "tenantId", mapping.tenantId());
    }

    private Map<String, Object> buildOrder(EventMap em, Map<String, Object> foreign) {
        Map<String, Object> po = new LinkedHashMap<>();
        po.put("state", "completed");
        po.put("relatedParty", List.of(Map.of("id", String.valueOf(path(foreign, em.paths().get("customerId"))),
                "role", "customer")));
        List<Map<String, Object>> items = new ArrayList<>();
        Object arr = path(foreign, em.paths().get("items"));
        if (arr instanceof List<?> list) {
            for (Object it : list) {
                if (it instanceof Map<?, ?> m) {
                    Object name = m.get(em.paths().get("itemName"));
                    if (name != null) {
                        items.add(Map.of("action", "add", "productOffering", Map.of("name", String.valueOf(name))));
                    }
                }
            }
        }
        po.put("productOrderItem", items);
        return po;
    }

    private Map<String, Object> buildIndividual(EventMap em, Map<String, Object> foreign) {
        Map<String, Object> ind = new LinkedHashMap<>();
        ind.put("id", String.valueOf(path(foreign, em.paths().get("id"))));
        Object fn = path(foreign, em.paths().get("firstName"));
        if (fn != null) ind.put("givenName", String.valueOf(fn));
        Object email = path(foreign, em.paths().get("email"));
        if (email != null) {
            ind.put("contactMedium", List.of(Map.of("mediumType", "email",
                    "characteristic", Map.of("emailAddress", String.valueOf(email)))));
        }
        return ind;
    }

    /** Generic: a customer-scoped event with relatedParty + flat fields copied
     * from configured paths (loyalty tier, churn band, bill amount…). */
    private Map<String, Object> buildKv(EventMap em, Map<String, Object> foreign) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("relatedParty", List.of(Map.of("id", String.valueOf(path(foreign, em.paths().get("customerId"))),
                "role", "customer")));
        for (Map.Entry<String, String> e : em.paths().entrySet()) {
            if ("customerId".equals(e.getKey())) continue;
            Object v = path(foreign, e.getValue());
            if (v != null) r.put(e.getKey(), v);
        }
        return r;
    }

    /** Resolve a dot-path (a.b.c) against nested maps. */
    private static Object path(Object node, String dotted) {
        if (dotted == null) return null;
        Object cur = node;
        for (String seg : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
        }
        return cur;
    }

    private record Mapping(String tenantId, String typeField, Map<String, EventMap> events) { }

    private record EventMap(String targetType, String topic, String resourceKey, String transform,
            Map<String, String> paths) { }
}
