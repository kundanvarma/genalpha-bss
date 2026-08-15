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
    private final com.bss.insight.repository.EmailSuppressionRepository suppressions;
    private final com.bss.insight.security.TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public BssTraitListener(PartyTraitService traits,
            com.bss.insight.repository.EmailSuppressionRepository suppressions,
            com.bss.insight.security.TenantScope tenantScope, ObjectMapper objectMapper) {
        this.traits = traits;
        this.suppressions = suppressions;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "#{'${bss.insight.trait-topics:bss.ordering.events,bss.party.events}'.split(',')}",
            groupId = "insight-traits")
    @SuppressWarnings("unchecked")
    public void onEvent(String payload) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, JSON);
            String eventType = String.valueOf(envelope.get("eventType"));
            String tenantId = envelope.get("tenantId") == null ? "genalpha"
                    : String.valueOf(envelope.get("tenantId"));
            Map<String, Object> event = envelope.get("event") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            // A customer's email, denormalised for BATCH activation (no per-row
            // party lookup when exporting an audience to an ad platform).
            if ("IndividualCreateEvent".equals(eventType)
                    || "IndividualAttributeValueChangeEvent".equals(eventType)) {
                if (event.get("individual") instanceof Map<?, ?> ind) {
                    Map<String, Object> individual = (Map<String, Object>) ind;
                    String partyId = individual.get("id") == null ? null : String.valueOf(individual.get("id"));
                    String email = emailOf(individual);
                    String region = individual.get("region") == null ? null : String.valueOf(individual.get("region"));
                    if (partyId != null) {
                        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                            if (email != null) traits.upsert(partyId, "email", email);
                            if (region != null && !region.isBlank()) traits.setTrait(partyId, "region", region);
                        }
                    }
                }
                return;
            }
            // B2B: an organization becomes an org-population candidate with its
            // own traits (industry…), marked so org audiences resolve only orgs.
            if ("OrganizationCreateEvent".equals(eventType) || "OrganizationAttributeValueChangeEvent".equals(eventType)) {
                Map<String, Object> org = resourceOf(event);
                String orgId = org == null || org.get("id") == null ? null : String.valueOf(org.get("id"));
                if (orgId != null) {
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        traits.setTrait(orgId, "_entity", "organization");
                        if (org.get("industry") != null) traits.setTrait(orgId, "industry", String.valueOf(org.get("industry")));
                        if (org.get("tradingName") != null) traits.setTrait(orgId, "orgName", String.valueOf(org.get("tradingName")));
                    }
                }
                return;
            }
            // Single-valued BSS traits that CHANGE over a lifetime — replace, so
            // an audience never matches a stale value. Real topics (loyalty,
            // intelligence, billing) or the bridge (foreign BSS) both land here.
            if ("LoyaltyTierChangedEvent".equals(eventType)) {
                setFrom(event, tenantId, "loyaltyTier", r -> str(r.get("tier")));
                return;
            }
            if ("ChurnRiskDetectedEvent".equals(eventType)) {
                setFrom(event, tenantId, "churnRisk", r -> str(r.get("band") != null ? r.get("band") : r.get("riskLevel")));
                return;
            }
            if ("CustomerBillCreateEvent".equals(eventType)) {
                setFrom(event, tenantId, "monthlySpend", BssTraitListener::billAmount);
                return;
            }
            // Engagement feedback loop: opens/clicks become an audience-able trait
            // (keyed by the engagement resource's partyId, not relatedParty).
            if ("EmailEngagedEvent".equals(eventType)) {
                Map<String, Object> r = resourceOf(event);
                String party = r == null || r.get("partyId") == null ? null : String.valueOf(r.get("partyId"));
                String eng = r == null || r.get("engagement") == null ? null : String.valueOf(r.get("engagement"));
                if (party != null && eng != null) {
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        traits.setTrait(party, "emailEngagement", "click".equals(eng) ? "clicked" : "opened");
                    }
                }
                return;
            }
            // DNC ledger projection: a suppressed address, for activation filtering.
            if ("EmailSuppressedEvent".equals(eventType)) {
                Map<String, Object> r = resourceOf(event);
                String email = r == null || r.get("email") == null ? null
                        : String.valueOf(r.get("email")).trim().toLowerCase();
                if (email != null && !email.isBlank()) {
                    try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                        if (!suppressions.existsByTenantIdAndEmail(tenantId, email)) {
                            com.bss.insight.entity.EmailSuppression s = new com.bss.insight.entity.EmailSuppression();
                            s.setId(java.util.UUID.randomUUID().toString());
                            s.setTenantId(tenantId);
                            s.setEmail(email);
                            s.setReason(r.get("reason") == null ? null : String.valueOf(r.get("reason")));
                            s.setCreatedAt(java.time.OffsetDateTime.now());
                            suppressions.save(s);
                        }
                    }
                }
                return;
            }
            if (!"ProductOrderStateChangeEvent".equals(eventType)) {
                return;
            }
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

    /** Extract a single-valued trait from an event's (only) resource and store it. */
    private void setFrom(Map<String, Object> event, String tenantId, String key,
            java.util.function.Function<Map<String, Object>, String> valueFn) {
        Map<String, Object> resource = resourceOf(event);
        if (resource == null) {
            return;
        }
        String party = customerOf(resource);
        String value = valueFn.apply(resource);
        if (party != null && value != null && !value.isBlank()) {
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                traits.setTrait(party, key, value);
            }
        }
    }

    /** The single resource an event envelope carries (event = {resourceKey: resource}). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourceOf(Map<String, Object> event) {
        for (Object v : event.values()) {
            if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** A bill's amount, from amountDue.value (TMF) or a flat amount field. */
    @SuppressWarnings("unchecked")
    private static String billAmount(Map<String, Object> bill) {
        if (bill.get("amountDue") instanceof Map<?, ?> m && m.get("value") != null) {
            return String.valueOf(m.get("value"));
        }
        if (bill.get("amount") != null) return String.valueOf(bill.get("amount"));
        return null;
    }

    @SuppressWarnings("unchecked")
    private String emailOf(Map<String, Object> individual) {
        if (!(individual.get("contactMedium") instanceof List<?> media)) {
            return null;
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> medium && "email".equalsIgnoreCase(String.valueOf(medium.get("mediumType")))
                    && medium.get("characteristic") instanceof Map<?, ?> c && c.get("emailAddress") != null) {
                return String.valueOf(c.get("emailAddress"));
            }
        }
        return null;
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
