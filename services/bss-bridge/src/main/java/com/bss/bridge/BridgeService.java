package com.bss.bridge;

import com.fasterxml.jackson.databind.JsonNode;
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
 *
 * <p>The foreign document arrives as a TREE and stays one: its shape belongs to
 * the source BSS, the bridge only resolves dot-paths into it. Every read keeps
 * the map's own leniency through {@link Json} — a path that resolves to nothing
 * still mints the literal {@code "null"} where it always did, and a key present
 * with a JSON null still counts as absent.</p>
 *
 * <p>The field maps are {@link LinkedHashMap}s, not {@code Map.of}: two of the
 * transforms ITERATE them into the published event, and a {@code Map.of}
 * re-salts its iteration order on every JVM start. The order below is the one
 * the running container was publishing.</p>
 */
@Service
public class BridgeService {

    private static final Logger log = LoggerFactory.getLogger(BridgeService.class);

    /** Per-source adapter config. Externalize to a mounted file per BSS in prod. */
    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "acme-bss", new Mapping("genalpha", "kind", events()));

    private static Map<String, EventMap> events() {
        Map<String, EventMap> m = new LinkedHashMap<>();
        m.put("ORDER_COMPLETED", new EventMap("ProductOrderStateChangeEvent", "bss.bridge.events",
                "productOrder", "order", paths("customerId", "account.ref", "items", "lines",
                        "itemName", "sku")));
        m.put("CUSTOMER_CREATED", new EventMap("IndividualCreateEvent", "bss.bridge.events",
                "individual", "individual", paths("id", "account.ref", "firstName",
                        "account.firstName", "email", "account.mail", "region", "region")));
        // single-valued BSS signals — relatedParty + one field, mapped generically
        m.put("LOYALTY_TIER", new EventMap("LoyaltyTierChangedEvent", "bss.bridge.events",
                "loyaltyMember", "kv", paths("customerId", "account.ref", "tier", "tier")));
        m.put("CHURN_SCORED", new EventMap("ChurnRiskDetectedEvent", "bss.bridge.events",
                "churnRisk", "kv", paths("customerId", "account.ref", "band", "band")));
        m.put("BILL_ISSUED", new EventMap("CustomerBillCreateEvent", "bss.bridge.events",
                "customerBill", "kv", paths("customerId", "account.ref", "amount", "amount")));
        // ORG_CREATED's extra fields are ITERATED into the event: tradingName
        // before industry is the order the wire already had.
        m.put("ORG_CREATED", new EventMap("OrganizationCreateEvent", "bss.bridge.events",
                "organization", "org", paths("orgId", "account.ref", "tradingName", "tradingName",
                        "industry", "industry")));
        return m;
    }

    private static Map<String, String> paths(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public BridgeService(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public BridgeReceipt ingest(String source, JsonNode foreign) {
        Mapping mapping = MAPPINGS.get(source);
        if (mapping == null) {
            throw new IllegalArgumentException("no adapter config for source BSS '" + source + "'");
        }
        String foreignType = Json.valueOf(path(foreign, mapping.typeField()));
        EventMap em = mapping.events().get(foreignType);
        if (em == null) {
            return BridgeReceipt.Ignored.unmapped(foreignType);
        }
        Map<String, Object> resource = switch (em.transform()) {
            case "order" -> buildOrder(em, foreign);
            case "individual" -> buildIndividual(em, foreign);
            case "org" -> buildOrg(em, foreign);
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
        return BridgeReceipt.Forwarded.of(em.targetType(), em.topic(), mapping.tenantId());
    }

    /** {@code {id, role}} — the order the published envelope already had. */
    private static Map<String, Object> customer(String partyId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", partyId);
        ref.put("role", "customer");
        return ref;
    }

    private Map<String, Object> buildOrder(EventMap em, JsonNode foreign) {
        Map<String, Object> po = new LinkedHashMap<>();
        po.put("state", "completed");
        po.put("relatedParty",
                List.of(customer(Json.valueOf(path(foreign, em.paths().get("customerId"))))));
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode arr = path(foreign, em.paths().get("items"));
        if (arr != null && arr.isArray()) {
            for (JsonNode it : arr) {
                if (it.isObject()) {
                    JsonNode name = it.get(em.paths().get("itemName"));
                    if (!Json.absent(name)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("productOffering", Map.of("name", Json.valueOf(name)));
                        item.put("action", "add");
                        items.add(item);
                    }
                }
            }
        }
        po.put("productOrderItem", items);
        return po;
    }

    private Map<String, Object> buildIndividual(EventMap em, JsonNode foreign) {
        Map<String, Object> ind = new LinkedHashMap<>();
        ind.put("id", Json.valueOf(path(foreign, em.paths().get("id"))));
        JsonNode fn = path(foreign, em.paths().get("firstName"));
        if (!Json.absent(fn)) ind.put("givenName", Json.valueOf(fn));
        JsonNode email = path(foreign, em.paths().get("email"));
        if (!Json.absent(email)) {
            Map<String, Object> medium = new LinkedHashMap<>();
            medium.put("characteristic", Map.of("emailAddress", Json.valueOf(email)));
            medium.put("mediumType", "email");
            ind.put("contactMedium", List.of(medium));
        }
        JsonNode region = path(foreign, em.paths().get("region"));
        if (!Json.absent(region)) ind.put("region", region);
        return ind;
    }

    /** An organization resource (its OWN id, not relatedParty). */
    private Map<String, Object> buildOrg(EventMap em, JsonNode foreign) {
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("id", Json.valueOf(path(foreign, em.paths().get("orgId"))));
        for (Map.Entry<String, String> e : em.paths().entrySet()) {
            if ("orgId".equals(e.getKey())) continue;
            JsonNode v = path(foreign, e.getValue());
            if (!Json.absent(v)) org.put(e.getKey(), v);
        }
        return org;
    }

    /** Generic: a customer-scoped event with relatedParty + flat fields copied
     * from configured paths (loyalty tier, churn band, bill amount…). */
    private Map<String, Object> buildKv(EventMap em, JsonNode foreign) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("relatedParty",
                List.of(customer(Json.valueOf(path(foreign, em.paths().get("customerId"))))));
        for (Map.Entry<String, String> e : em.paths().entrySet()) {
            if ("customerId".equals(e.getKey())) continue;
            JsonNode v = path(foreign, e.getValue());
            if (!Json.absent(v)) r.put(e.getKey(), v);
        }
        return r;
    }

    /**
     * Resolve a dot-path (a.b.c) against the foreign document. A segment that
     * is not an object ends the walk, exactly as the nested-map version did —
     * and a missing leaf returns Java null so {@link Json} can mint the same
     * literal {@code "null"} the old {@code String.valueOf} minted.
     */
    static JsonNode path(JsonNode node, String dotted) {
        if (dotted == null) return null;
        JsonNode cur = node;
        for (String seg : dotted.split("\\.")) {
            if (cur == null || !cur.isObject()) return null;
            cur = cur.get(seg);
        }
        return cur;
    }

    private record Mapping(String tenantId, String typeField, Map<String, EventMap> events) { }

    private record EventMap(String targetType, String topic, String resourceKey, String transform,
            Map<String, String> paths) { }
}
