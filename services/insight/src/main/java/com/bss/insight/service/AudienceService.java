package com.bss.insight.service;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.client.AnalyticsForwarder;
import com.bss.insight.entity.Audience;
import com.bss.insight.entity.PartyTrait;
import com.bss.insight.entity.Prospect;
import com.bss.insight.entity.VisitorProfile;
import com.bss.insight.repository.AudienceRepository;
import com.bss.insight.repository.PartyTraitRepository;
import com.bss.insight.repository.ProspectRepository;
import com.bss.insight.repository.VisitorEventRepository;
import com.bss.insight.repository.VisitorProfileRepository;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saved audiences with a criteria tree, evaluated against the consented,
 * stitched profiles this service holds. The tree is the marketer-friendly
 * replacement for a bare segment string.
 */
@Service
public class AudienceService {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final AudienceRepository audiences;
    private final VisitorProfileRepository profiles;
    private final VisitorEventRepository events;
    private final PartyTraitRepository traits;
    private final ProspectRepository prospects;
    private final AnalyticsForwarder analytics;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public AudienceService(AudienceRepository audiences, VisitorProfileRepository profiles,
            VisitorEventRepository events, PartyTraitRepository traits, ProspectRepository prospects,
            AnalyticsForwarder analytics, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.audiences = audiences;
        this.profiles = profiles;
        this.events = events;
        this.traits = traits;
        this.prospects = prospects;
        this.analytics = analytics;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("criteria") == null) {
            throw new IllegalArgumentException("name and criteria are required");
        }
        Audience a = new Audience();
        String id = UUID.randomUUID().toString();
        a.setId(id);
        a.setHref(ApiConstants.BASE_PATH + "/audience/" + id);
        a.setTenantId(tenantScope.currentTenantId());
        a.setName(String.valueOf(dto.get("name")));
        a.setCriteria(serialize(dto.get("criteria")));
        a.setPopulation("prospect".equals(dto.get("population")) ? "prospect" : "customer");
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        return toMap(audiences.save(a));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return audiences.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toMap(load(id));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Audience a = load(id);
        if (patch.get("name") != null) a.setName(String.valueOf(patch.get("name")));
        if (patch.get("criteria") != null) a.setCriteria(serialize(patch.get("criteria")));
        a.setLastUpdate(OffsetDateTime.now());
        return toMap(audiences.save(a));
    }

    /**
     * Resolve the audience: evaluate the criteria tree over the BSS-native
     * candidate base — every party the BSS holds a TRAIT about (its own
     * customers), UNIONED with every consented, stitched browser profile.
     *
     * <p>This is the point of BSS-native audiences: a customer who matches on
     * first-party BSS data (a product they hold, a churn band) is reachable
     * WITHOUT ever having browsed or been exported to a marketing tool. Behaviour
     * signals (interest/audience leaves) still ride the personalization-consent
     * spine — they only have values for a consented profile. Marketing consent
     * and DNC are enforced again at SEND (communication + DNC), so the resolved
     * set is "who matches", not "who may be messaged regardless".
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(String id) {
        Audience audience = load(id);
        Object criteria = parse(audience.getCriteria());
        String tenantId = tenantScope.currentTenantId();
        if ("prospect".equals(audience.getPopulation())) {
            return prospectMembers(criteria, tenantId);
        }
        // Only touch the signals the tree actually references — a pure trait
        // audience must not pay for per-row browsing/GA4 work.
        boolean usesBehaviour = treeUses(criteria, "interest");
        boolean usesGa4 = treeUses(criteria, "audience");
        // consented browser profiles, one per party (behaviour signals live here)
        Map<String, VisitorProfile> profileByParty = new LinkedHashMap<>();
        for (VisitorProfile p : profiles.findByTenantIdAndPartyIdIsNotNull(tenantId)) {
            if (p.isPersonalizationConsent()) {
                profileByParty.putIfAbsent(p.getPartyId(), p);
            }
        }
        // Traits in ONE query, grouped party -> key -> values (not a query per
        // candidate). A behavioural tree uses this in-memory path; a trait-ONLY
        // tree goes through set-based SQL (see members()'s SQL branch).
        Map<String, Map<String, Set<String>>> traitsByParty = new LinkedHashMap<>();
        for (PartyTrait t : traits.findByTenantId(tenantId)) {
            traitsByParty.computeIfAbsent(t.getPartyId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(t.getTraitKey(), k -> new LinkedHashSet<>())
                    .add(t.getTraitValue());
        }
        // candidate base = trait-carrying customers ∪ consented profiles
        Set<String> candidates = new LinkedHashSet<>(traitsByParty.keySet());
        candidates.addAll(profileByParty.keySet());
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String partyId : candidates) {
            Set<String> interests = new LinkedHashSet<>();
            List<String> ga4 = List.of();
            VisitorProfile prof = profileByParty.get(partyId);
            if (prof != null && usesBehaviour) {
                for (Object[] row : events.interestsOf(tenantId, prof.getVisitorId())) {
                    if (row.length > 0 && row[0] != null) interests.add(String.valueOf(row[0]));
                }
            }
            if (prof != null && usesGa4) {
                ga4 = analytics.audiencesOf(tenantId, prof.getVisitorId());
            }
            Map<String, Set<String>> byKey = traitsByParty.getOrDefault(partyId, Map.of());
            if (matches(criteria, interests, ga4, byKey)) {
                out.add(Map.of("partyId", partyId));
            }
        }
        return out;
    }

    /** Does the criteria tree reference a leaf of this type anywhere? Lets member
     * resolution skip signals no rule uses (no per-row browsing/GA4 fan-out). */
    @SuppressWarnings("unchecked")
    private boolean treeUses(Object node, String leafType) {
        if (!(node instanceof Map<?, ?> m)) {
            return false;
        }
        if (m.get("all") instanceof List<?> all) return all.stream().anyMatch(c -> treeUses(c, leafType));
        if (m.get("any") instanceof List<?> any) return any.stream().anyMatch(c -> treeUses(c, leafType));
        if (m.get("not") != null) return treeUses(m.get("not"), leafType);
        return leafType.equals(String.valueOf(m.get("type")));
    }

    /**
     * Resolve a PROSPECT audience: consented prospects matching the source tree.
     * The consent gate is here and absolute — an {@code unconsented} prospect (a
     * bought list, an unverified import) is NEVER returned, so a downstream send
     * can only ever reach a contact the operator may lawfully message.
     */
    private List<Map<String, Object>> prospectMembers(Object criteria, String tenantId) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        boolean emptyTree = !(criteria instanceof Map<?, ?> m)
                || (!(m.get("all") instanceof List<?> a && !a.isEmpty())
                    && !(m.get("any") instanceof List<?> o && !o.isEmpty()) && m.get("not") == null && m.get("type") == null);
        for (Prospect p : prospects.findByTenantId(tenantId)) {
            if (!Prospect.CONSENTED.equals(p.getConsent())) {
                continue; // the consent gate — captured, not reachable
            }
            Map<String, Set<String>> facts = new LinkedHashMap<>();
            if (p.getSource() != null) facts.put("source", Set.of(p.getSource()));
            if (p.getConsent() != null) facts.put("consent", Set.of(p.getConsent()));
            if (emptyTree || matches(criteria, Set.of(), List.of(), facts)) {
                out.add(Map.of("prospectId", p.getId(), "email", p.getEmail() == null ? "" : p.getEmail(),
                        "consent", p.getConsent()));
            }
        }
        return out;
    }

    /** The facets a builder can offer as real choices: the BSS traits this
     * tenant actually holds, grouped so the UI can present key -> values. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> facets() {
        String tenantId = tenantScope.currentTenantId();
        return traits.distinctKeyValues(tenantId).stream()
                .map(kv -> Map.<String, Object>of("key", kv[0], "value", kv[1]))
                .toList();
    }

    /** Recursive evaluation: {all|any:[..]} | {not:{..}} | {type,key?,op?,value} leaf. */
    @SuppressWarnings("unchecked")
    private boolean matches(Object node, Set<String> interests, List<String> ga4,
            Map<String, Set<String>> traitsByKey) {
        if (!(node instanceof Map<?, ?> m)) {
            return false;
        }
        if (m.get("all") instanceof List<?> all) {
            return all.stream().allMatch(c -> matches(c, interests, ga4, traitsByKey));
        }
        if (m.get("any") instanceof List<?> any) {
            return any.stream().anyMatch(c -> matches(c, interests, ga4, traitsByKey));
        }
        if (m.get("not") != null) {
            return !matches(m.get("not"), interests, ga4, traitsByKey);
        }
        String type = String.valueOf(m.get("type"));
        String value = String.valueOf(m.get("value"));
        return switch (type) {
            case "interest" -> interests.contains(value);            // first-party behaviour
            case "audience" -> ga4.contains(value);                  // analytics-computed audience
            case "trait" -> matchesTrait(traitsByKey.get(String.valueOf(m.get("key"))),
                    m.get("op") == null ? "eq" : String.valueOf(m.get("op")), value); // BSS-native customer data
            case "source" -> hasValue(traitsByKey.get("source"), value);   // prospect: where the lead came from
            case "consent" -> hasValue(traitsByKey.get("consent"), value); // prospect: consent state
            default -> false;                                         // unknown leaf never matches
        };
    }

    private static boolean hasValue(Set<String> values, String value) {
        return values != null && values.contains(value);
    }

    /** A trait leaf honours a comparison OP: eq (default, membership) or a
     * numeric gte/lte/gt/lt (e.g. monthlySpend >= 50). Non-numeric values never
     * satisfy a numeric op. */
    private static boolean matchesTrait(Set<String> values, String op, String value) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        if (op == null || op.isBlank() || "eq".equals(op)) {
            return values.contains(value);
        }
        Double target = num(value);
        if (target == null) {
            return false;
        }
        for (String v : values) {
            Double n = num(v);
            if (n == null) continue;
            boolean ok = switch (op) {
                case "gte" -> n >= target;
                case "lte" -> n <= target;
                case "gt" -> n > target;
                case "lt" -> n < target;
                default -> false;
            };
            if (ok) return true;
        }
        return false;
    }

    private static Double num(String s) {
        try {
            return Double.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Audience load(String id) {
        return audiences.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new IllegalArgumentException("audience not found: " + id));
    }

    private String serialize(Object criteria) {
        try {
            String json = criteria instanceof String s ? s : objectMapper.writeValueAsString(criteria);
            objectMapper.readValue(json, OBJECT); // validate it parses as an object
            return json;
        } catch (Exception e) {
            throw new IllegalArgumentException("criteria must be a JSON object tree");
        }
    }

    private Object parse(String criteria) {
        try {
            return objectMapper.readValue(criteria == null ? "{}" : criteria, OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> toMap(Audience a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("href", a.getHref());
        map.put("name", a.getName());
        map.put("population", a.getPopulation() == null ? "customer" : a.getPopulation());
        try {
            map.put("criteria", objectMapper.readValue(a.getCriteria() == null ? "{}" : a.getCriteria(), OBJECT));
        } catch (Exception e) {
            map.put("criteria", a.getCriteria());
        }
        map.put("lastUpdate", a.getLastUpdate());
        map.put("@type", "Audience");
        return map;
    }
}
