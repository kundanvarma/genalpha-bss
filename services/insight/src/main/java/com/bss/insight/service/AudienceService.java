package com.bss.insight.service;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.client.AnalyticsForwarder;
import com.bss.insight.dto.AudienceExplain;
import com.bss.insight.dto.AudienceMember;
import com.bss.insight.dto.AudienceRefreshReceipt;
import com.bss.insight.dto.AudienceRequest;
import com.bss.insight.dto.AudienceView;
import com.bss.insight.dto.Facet;
import com.bss.insight.entity.Audience;
import com.bss.insight.entity.AudienceSnapshot;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.bss.insight.api.Wire.textOf;

/**
 * Saved audiences with a criteria tree, evaluated against the consented,
 * stitched profiles this service holds. The tree is the marketer-friendly
 * replacement for a bare segment string — a document the marketer authored,
 * kept verbatim ({@link JsonNode} on the wire) and evaluated here.
 */
@Service
public class AudienceService {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final AudienceRepository audiences;
    private final VisitorProfileRepository profiles;
    private final VisitorEventRepository events;
    private final PartyTraitRepository traits;
    private final ProspectRepository prospects;
    private final com.bss.insight.repository.AudienceSnapshotRepository snapshots;
    private final AnalyticsForwarder analytics;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public AudienceService(AudienceRepository audiences, VisitorProfileRepository profiles,
            VisitorEventRepository events, PartyTraitRepository traits, ProspectRepository prospects,
            com.bss.insight.repository.AudienceSnapshotRepository snapshots,
            AnalyticsForwarder analytics, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.audiences = audiences;
        this.profiles = profiles;
        this.events = events;
        this.traits = traits;
        this.prospects = prospects;
        this.snapshots = snapshots;
        this.analytics = analytics;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    /**
     * Materialize the audience: freeze its resolved members into a snapshot so
     * hot audiences read instantly (no recompute) and membership is stable
     * between refreshes. On-demand here; a cron or the console drives it on a
     * cadence in production.
     */
    @Transactional
    public AudienceRefreshReceipt refresh(String id) {
        Resolved r = resolve(id);
        Audience a = load(id);
        String tenantId = tenantScope.currentTenantId();
        snapshots.deleteByTenantIdAndAudienceId(tenantId, id);
        OffsetDateTime now = OffsetDateTime.now();
        for (AudienceMember m : r.members()) {
            AudienceSnapshot s = new AudienceSnapshot();
            s.setId(UUID.randomUUID().toString());
            s.setTenantId(tenantId);
            s.setAudienceId(id);
            s.setPartyId(m.partyId());
            s.setEmail(m.email());
            s.setCreatedAt(now);
            snapshots.save(s);
        }
        a.setMaterializedAt(now);
        a.setMemberCount(r.members().size());
        audiences.save(a);
        return new AudienceRefreshReceipt(id, r.path(), r.members().size(), now);
    }

    /** The frozen member set — instant, no recompute. */
    @Transactional(readOnly = true)
    public List<AudienceMember> snapshotMembers(String id) {
        return snapshots.findByTenantIdAndAudienceId(tenantScope.currentTenantId(), id).stream()
                .map(s -> AudienceMember.snapshot(s.getPartyId(), s.getEmail())).toList();
    }

    @Transactional
    public AudienceView create(AudienceRequest dto) {
        if (dto.name() == null || !dto.hasCriteria()) {
            throw new IllegalArgumentException("name and criteria are required");
        }
        Audience a = new Audience();
        String id = UUID.randomUUID().toString();
        a.setId(id);
        a.setHref(ApiConstants.BASE_PATH + "/audience/" + id);
        a.setTenantId(tenantScope.currentTenantId());
        a.setName(dto.name());
        a.setCriteria(serialize(dto.criteria()));
        a.setPopulation("prospect".equals(dto.population()) ? "prospect"
                : "organization".equals(dto.population()) ? "organization"
                : "visitor".equals(dto.population()) ? "visitor" : "customer");
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        return view(audiences.save(a));
    }

    @Transactional(readOnly = true)
    public List<AudienceView> list() {
        return audiences.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AudienceView get(String id) {
        return view(load(id));
    }

    @Transactional
    public AudienceView patch(String id, AudienceRequest patch) {
        Audience a = load(id);
        if (patch.name() != null) a.setName(patch.name());
        if (patch.hasCriteria()) a.setCriteria(serialize(patch.criteria()));
        a.setLastUpdate(OffsetDateTime.now());
        return view(audiences.save(a));
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
    public List<AudienceMember> members(String id, Integer limit) {
        List<AudienceMember> resolved = resolve(id).members();
        // A preview (console drill-down) bounds the work: only enrich what it shows,
        // never a million rows. limit == null means the full set (activation export).
        List<AudienceMember> raw = limit != null && limit > 0 && resolved.size() > limit
                ? resolved.subList(0, limit) : resolved;
        // Enrich party members with a human label (email) so the console shows WHO
        // is in the audience, not just an opaque id. One query for all emails, then a map.
        java.util.List<String> partyIds = raw.stream()
                .filter(m -> m.partyId() != null && m.email() == null)
                .map(AudienceMember::partyId).distinct().toList();
        if (partyIds.isEmpty()) {
            return raw;
        }
        // Fetch emails for ONLY these members, chunked — index-driven, O(members),
        // not a whole-tenant scan (so it stays fast as the trait store grows).
        String tenantId = tenantScope.currentTenantId();
        Map<String, String> emailByParty = new java.util.HashMap<>();
        for (int i = 0; i < partyIds.size(); i += 1000) {
            List<String> chunk = partyIds.subList(i, Math.min(i + 1000, partyIds.size()));
            for (com.bss.insight.entity.PartyTrait t
                    : traits.findByTenantIdAndTraitKeyAndPartyIdIn(tenantId, "email", chunk)) {
                emailByParty.putIfAbsent(t.getPartyId(), t.getTraitValue());
            }
        }
        List<AudienceMember> out = new java.util.ArrayList<>(raw.size());
        for (AudienceMember m : raw) {
            String email = m.partyId() != null && m.email() == null ? emailByParty.get(m.partyId()) : null;
            out.add(email != null ? m.withEmail(email) : m);
        }
        return out;
    }

    /** Delete a saved audience (and its snapshot), so the list stays clean —
     * audiences pile up otherwise. Tenant-scoped: load() 404s on a foreign id. */
    @Transactional
    public void delete(String id) {
        Audience a = load(id);
        snapshots.deleteByTenantIdAndAudienceId(tenantScope.currentTenantId(), id);
        audiences.delete(a);
    }

    /** Same resolution, but reports WHICH path ran (sql | memory | prospect) —
     * so ops (and a test) can confirm a trait-only audience takes the scalable
     * set-based SQL path rather than the in-memory fallback. */
    @Transactional(readOnly = true)
    public AudienceExplain membersExplain(String id) {
        Resolved r = resolve(id);
        return new AudienceExplain(r.path(), r.members().size(), r.members());
    }

    private Resolved resolve(String id) {
        Audience audience = load(id);
        Object criteria = parse(audience.getCriteria());
        String tenantId = tenantScope.currentTenantId();
        if ("prospect".equals(audience.getPopulation())) {
            return new Resolved("prospect", prospectMembers(criteria, tenantId));
        }
        if ("visitor".equals(audience.getPopulation())) {
            return new Resolved("visitor", visitorMembers(criteria, tenantId));
        }
        // B2B: resolve over ORGANIZATIONS only — the org marker is always ANDed,
        // plus the user's (trait-only) tree over org attributes (industry…).
        if ("organization".equals(audience.getPopulation())) {
            java.util.List<Object> clauses = new java.util.ArrayList<>();
            clauses.add(Map.of("type", "trait", "key", "_entity", "value", "organization"));
            if (traitOnly(criteria)) clauses.add(criteria);
            return new Resolved("sql", setBasedMembers(Map.of("all", clauses), tenantId));
        }
        // A trait-ONLY tree compiles to set-based SQL over party_trait — indexed,
        // INTERSECT/UNION/EXCEPT, scales to millions. Behavioural trees (interest/
        // GA4) still use the in-memory path over the bounded consented-profile set.
        if (traitOnly(criteria)) {
            return new Resolved("sql", setBasedMembers(criteria, tenantId));
        }
        return new Resolved("memory", inMemoryMembers(criteria, tenantId));
    }

    /** Every leaf is a trait leaf (so the tree is a pure BSS-data segment). */
    @SuppressWarnings("unchecked")
    private boolean traitOnly(Object node) {
        if (!(node instanceof Map<?, ?> m)) {
            return false;
        }
        if (m.get("all") instanceof List<?> l) return !l.isEmpty() && l.stream().allMatch(this::traitOnly);
        if (m.get("any") instanceof List<?> l) return !l.isEmpty() && l.stream().allMatch(this::traitOnly);
        if (m.get("not") != null) return traitOnly(m.get("not"));
        return "trait".equals(String.valueOf(m.get("type")));
    }

    /**
     * Set-based membership: compile the trait tree to native SQL over party_trait
     * (leaf → indexed SELECT of party_ids; all → INTERSECT; any → UNION; not →
     * all-trait-parties EXCEPT child). One query, no per-row loop — the real
     * scale path. RLS + an explicit tenant filter keep it tenant-scoped.
     */
    private List<AudienceMember> setBasedMembers(Object criteria, String tenantId) {
        SqlCtx ctx = new SqlCtx();
        String sql = "SELECT DISTINCT party_id FROM " + compile(criteria, ctx) + " AS seg";
        jakarta.persistence.Query q = em.createNativeQuery(sql);
        q.setParameter("t", tenantId);
        ctx.params.forEach(q::setParameter);
        List<?> rows = q.getResultList();
        List<AudienceMember> out = new java.util.ArrayList<>(rows.size());
        for (Object r : rows) {
            out.add(AudienceMember.party(String.valueOf(r)));
        }
        return out;
    }

    private String allTraitParties() {
        return "(SELECT DISTINCT party_id FROM party_trait WHERE tenant_id = :t)";
    }

    @SuppressWarnings("unchecked")
    private String compile(Object node, SqlCtx ctx) {
        Map<String, Object> m = (Map<String, Object>) node;
        if (m.get("all") instanceof List<?> l) {
            return "(" + l.stream().map(c -> compile(c, ctx)).reduce((a, b) -> a + " INTERSECT " + b).orElse(allTraitParties()) + ")";
        }
        if (m.get("any") instanceof List<?> l) {
            return "(" + l.stream().map(c -> compile(c, ctx)).reduce((a, b) -> a + " UNION " + b).orElse(allTraitParties()) + ")";
        }
        if (m.get("not") != null) {
            return "(" + allTraitParties() + " EXCEPT " + compile(m.get("not"), ctx) + ")";
        }
        int i = ctx.n++;
        String key = textOf(m, "key"); // a rule without a key binds null and matches no trait, not the trait "null"
        String op = m.get("op") == null ? "eq" : String.valueOf(m.get("op"));
        String value = String.valueOf(m.get("value"));
        ctx.params.put("k" + i, key);
        if ("eq".equals(op) || op.isBlank()) {
            ctx.params.put("v" + i, value);
            return "(SELECT party_id FROM party_trait WHERE tenant_id = :t AND trait_key = :k" + i
                    + " AND trait_value = :v" + i + ")";
        }
        String cmp = switch (op) {
            case "gte" -> ">="; case "lte" -> "<="; case "gt" -> ">"; case "lt" -> "<"; default -> null;
        };
        if (cmp == null || num(value) == null) {
            return "(SELECT party_id FROM party_trait WHERE 1 = 0)"; // malformed op → matches nobody
        }
        ctx.params.put("v" + i, num(value));
        // '?'-free regex (a native-query '?' would be read as a positional param);
        // NULL-guarded cast so a non-numeric value for this key never errors.
        return "(SELECT party_id FROM party_trait WHERE tenant_id = :t AND trait_key = :k" + i
                + " AND CAST(CASE WHEN trait_value ~ '^[-]{0,1}[0-9]+([.][0-9]+){0,1}$'"
                + " THEN trait_value ELSE NULL END AS DOUBLE PRECISION) " + cmp + " :v" + i + ")";
    }

    private static final class SqlCtx {
        int n = 0;
        final Map<String, Object> params = new LinkedHashMap<>();
    }

    private record Resolved(String path, List<AudienceMember> members) { }

    private List<AudienceMember> inMemoryMembers(Object criteria, String tenantId) {
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
        List<AudienceMember> out = new java.util.ArrayList<>();
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
                out.add(AudienceMember.party(partyId));
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
    private List<AudienceMember> prospectMembers(Object criteria, String tenantId) {
        List<AudienceMember> out = new java.util.ArrayList<>();
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
                out.add(AudienceMember.prospect(p.getId(), p.getEmail() == null ? "" : p.getEmail(), p.getConsent()));
            }
        }
        return out;
    }

    /**
     * Resolve a VISITOR audience: consented browser profiles (incl. anonymous)
     * matching the interest tree — a retargeting segment keyed by visitorId, no
     * account required. The identifier is a cookie/device id (not email), so this
     * feeds on-site personalization + web retargeting lists, not email export.
     */
    private List<AudienceMember> visitorMembers(Object criteria, String tenantId) {
        boolean emptyTree = !(criteria instanceof Map<?, ?> m)
                || (!(m.get("all") instanceof List<?> a && !a.isEmpty())
                    && !(m.get("any") instanceof List<?> o && !o.isEmpty()) && m.get("not") == null && m.get("type") == null);
        List<AudienceMember> out = new java.util.ArrayList<>();
        for (VisitorProfile p : profiles.findByTenantId(tenantId)) {
            if (!p.isPersonalizationConsent()) {
                continue; // retargeting rides the personalization-consent spine
            }
            Set<String> interests = new LinkedHashSet<>();
            for (Object[] row : events.interestsOf(tenantId, p.getVisitorId())) {
                if (row.length > 0 && row[0] != null) interests.add(String.valueOf(row[0]));
            }
            if (emptyTree || matches(criteria, interests, List.of(), Map.of())) {
                out.add(AudienceMember.visitor(p.getVisitorId(), p.getPartyId()));
            }
        }
        return out;
    }

    /** The facets a builder can offer as real choices: the BSS traits this
     * tenant actually holds, grouped so the UI can present key -> values. */
    @Transactional(readOnly = true)
    public List<Facet> facets() {
        String tenantId = tenantScope.currentTenantId();
        return traits.distinctKeyValues(tenantId).stream()
                .map(kv -> new Facet(kv[0] == null ? null : String.valueOf(kv[0]),
                        kv[1] == null ? null : String.valueOf(kv[1])))
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
            case "trait" -> matchesTrait(traitsByKey.get(textOf(m, "key")),
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

    /** The tree as the marketer sent it — a JSON string or an object — stored as text after a parse check. */
    private String serialize(JsonNode criteria) {
        try {
            String json = criteria.isTextual() ? criteria.asText() : objectMapper.writeValueAsString(criteria);
            objectMapper.readValue(json, OBJECT); // validate it parses as an object
            return json;
        } catch (Exception e) {
            throw new IllegalArgumentException("criteria must be a JSON object tree");
        }
    }

    /** The evaluation form of the stored tree: nested maps and lists the matcher walks. */
    private Object parse(String criteria) {
        try {
            return objectMapper.readValue(criteria == null ? "{}" : criteria, OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private AudienceView view(Audience a) {
        JsonNode criteria;
        try {
            criteria = objectMapper.readTree(a.getCriteria() == null ? "{}" : a.getCriteria());
        } catch (Exception e) {
            criteria = TextNode.valueOf(a.getCriteria());
        }
        return new AudienceView(a.getId(), a.getHref(), a.getName(),
                a.getPopulation() == null ? "customer" : a.getPopulation(),
                a.getMaterializedAt(), a.getMemberCount(), criteria, a.getLastUpdate(), "Audience");
    }
}
