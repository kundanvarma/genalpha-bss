package com.bss.insight.desk;

import com.bss.insight.entity.DeskDecision;
import com.bss.insight.entity.DeskEvent;
import com.bss.insight.entity.DeskPreset;
import com.bss.insight.repository.DeskDecisionRepository;
import com.bss.insight.repository.DeskEventRepository;
import com.bss.insight.repository.DeskPresetRepository;
import com.bss.insight.security.TenantRegistry;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DESK LEARNING, slice 1: the BSS as its own customer. Desks report what staff
 * DO (a tab opened, a form started, a field focused, a form submitted or
 * abandoned, an empty search, a copilot draft rewritten) — never what they see,
 * never a customer. The service turns a week of that into a FRICTION REPORT
 * and into SUGGESTIONS, each with its evidence and, where the fix is safe, a
 * one-click action:
 * <ul>
 *   <li>preset — the same form submitted 3+ times with the same values by one
 *       person → save those values as a preset for the form</li>
 *   <li>holdout — a journey created without a holdout → add one (the desk runs
 *       the PATCH with the user's own token; insight holds no cross-service credential)</li>
 *   <li>abandon — a form started and left; where people stop</li>
 *   <li>search — searches that found nothing</li>
 *   <li>rewrite — copilot drafts rewritten before use</li>
 *   <li>unused — features never opened this period</li>
 * </ul>
 * Everything is per tenant and switchable (tenants.yml desk-learning). The
 * anonymised export carries counts only: no hashes, no values, no names.
 */
@Service
public class DeskLearningService {

    private static final Set<String> PRESET_IGNORE = Set.of("name", "id", "description", "subject", "content",
            "steps", "code", "email", "phone", "msisdn", "href");
    private final DeskEventRepository events;
    private final DeskPresetRepository presets;
    private final DeskDecisionRepository decisions;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final ObjectMapper json;

    public DeskLearningService(DeskEventRepository events, DeskPresetRepository presets,
            DeskDecisionRepository decisions, TenantScope tenantScope, TenantRegistry registry, ObjectMapper json) {
        this.events = events;
        this.presets = presets;
        this.decisions = decisions;
        this.tenantScope = tenantScope;
        this.registry = registry;
        this.json = json;
    }

    public boolean enabled() {
        TenantRegistry.TenantEntry t = registry.byId(tenantScope.currentTenantId());
        return t != null && t.isDeskLearning();
    }

    /* ------------------------------------------------------------------ ingest */

    @Transactional
    public Map<String, Object> ingest(List<Map<String, Object>> batch) {
        if (!enabled()) {
            return Map.of("accepted", 0, "enabled", false);
        }
        String tenant = tenantScope.currentTenantId();
        String actor = actorHash(tenant);
        int n = 0;
        for (Map<String, Object> e : batch == null ? List.<Map<String, Object>>of() : batch) {
            String event = str(e.get("event"));
            if (event == null || event.isBlank()) {
                continue;
            }
            DeskEvent d = new DeskEvent();
            d.setId(UUID.randomUUID().toString());
            d.setTenantId(tenant);
            d.setActorHash(actor);
            d.setSessionId(trim(str(e.get("session")), 64));
            d.setDesk(trim(str(e.getOrDefault("desk", "console")), 32));
            d.setEvent(trim(event, 32));
            d.setTarget(trim(str(e.get("target")), 128));
            Object props = e.get("props");
            try {
                d.setProps(props == null ? null : trim(json.writeValueAsString(scrub(props)), 4000));
            } catch (Exception ex) {
                d.setProps(null);
            }
            d.setOccurredAt(OffsetDateTime.now());
            events.save(d);
            n++;
        }
        return Map.of("accepted", n, "enabled", true);
    }

    /** No free text that could carry a customer: values are kept only for short, enumerable fields. */
    @SuppressWarnings("unchecked")
    private Object scrub(Object props) {
        if (!(props instanceof Map<?, ?> m)) {
            return props;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : m.entrySet()) {
            String k = String.valueOf(en.getKey());
            Object v = en.getValue();
            if ("values".equals(k) && v instanceof Map<?, ?> vals) {
                Map<String, Object> kept = new LinkedHashMap<>();
                for (Map.Entry<?, ?> ve : vals.entrySet()) {
                    String key = String.valueOf(ve.getKey());
                    String val = ve.getValue() == null ? "" : String.valueOf(ve.getValue());
                    if (PRESET_IGNORE.contains(key)) {
                        kept.put(key, val.isBlank() ? "" : "·"); // presence only
                    } else {
                        kept.put(key, trim(val, 80));
                    }
                }
                out.put(k, kept);
            } else if (v instanceof String s) {
                out.put(k, trim(s, 200));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ friction */

    @Transactional(readOnly = true)
    public Map<String, Object> friction(int days) {
        String tenant = tenantScope.currentTenantId();
        List<DeskEvent> all = events.findByTenantIdAndOccurredAtAfterOrderByOccurredAtAsc(tenant,
                OffsetDateTime.now().minusDays(Math.max(1, Math.min(days, 90))));
        Analysis a = analyse(all);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("days", days);
        out.put("events", all.size());
        out.put("activeStaff", all.stream().map(DeskEvent::getActorHash).collect(Collectors.toSet()).size());
        out.put("abandonedForms", a.abandoned);
        out.put("repeatedForms", a.repeated);
        out.put("emptySearches", a.emptySearches);
        out.put("copilotRewrites", a.rewrites);
        out.put("unusedFeatures", a.unused);
        out.put("journeysWithoutHoldout", a.noHoldout);
        return out;
    }

    /* ------------------------------------------------------------------ suggestions */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> suggestions() {
        String tenant = tenantScope.currentTenantId();
        List<DeskEvent> all = events.findByTenantIdAndOccurredAtAfterOrderByOccurredAtAsc(tenant, OffsetDateTime.now().minusDays(7));
        Analysis a = analyse(all);
        Set<String> quiet = decisions.findByTenantIdAndDecidedAtAfter(tenant, OffsetDateTime.now().minusDays(30))
                .stream().map(DeskDecision::getSuggestionId).collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : a.repeated) {
            Map<String, Object> common = (Map<String, Object>) r.get("commonValues");
            if (common == null || common.isEmpty()) {
                continue;
            }
            out.add(suggestion("preset", str(r.get("form")), "Save a preset for the " + r.get("form") + " form",
                    r.get("count") + " submissions in 7 days shared the same " + common.keySet() + " — one click would prefill them.",
                    "operator", Map.of("kind", "preset", "desk", str(r.get("desk")), "form", str(r.get("form")), "values", common), quiet));
        }
        for (Map<String, Object> j : a.noHoldout) {
            out.add(suggestion("holdout", str(j.get("journeyId")), "Add a holdout to the journey \"" + j.get("name") + "\"",
                    "Created without a control group, so its lift cannot be measured. 10 % held out keeps the claim honest.",
                    "operator", Map.of("kind", "action", "method", "PATCH",
                            "path", "/tmf-api/campaignManagement/v4/journey/" + j.get("journeyId"),
                            "body", Map.of("holdoutPercent", 10)), quiet));
        }
        for (Map<String, Object> ab : a.abandoned) {
            if ((int) ab.get("count") >= 2) {
                out.add(suggestion("abandon", str(ab.get("form")), "People start the " + ab.get("form") + " form and leave",
                        ab.get("count") + " abandoned starts; most stop at \"" + ab.getOrDefault("stopField", "?") + "\". "
                                + "A default, a hint or a preset there would help.", "vendor", null, quiet));
            }
        }
        for (Map<String, Object> s : a.emptySearches) {
            if ((int) s.get("count") >= 2) {
                out.add(suggestion("search", str(s.get("query")), "Searches for \"" + s.get("query") + "\" find nothing",
                        s.get("count") + " times this week. A knowledge article, an alias or a filter would answer it.",
                        "operator", null, quiet));
            }
        }
        for (Map<String, Object> rw : a.rewrites) {
            if ((int) rw.get("count") >= 2) {
                out.add(suggestion("rewrite", str(rw.get("form")), "Copilot drafts for " + rw.get("form") + " are rewritten before use",
                        rw.get("count") + " drafts changed by more than half. The prompt or its defaults are off for this tenant.",
                        "vendor", null, quiet));
            }
        }
        if (!a.unused.isEmpty()) {
            out.add(suggestion("unused", "features", a.unused.size() + " features were never opened this week",
                    String.join(", ", a.unused.stream().limit(8).toList()) + (a.unused.size() > 8 ? ", …" : "")
                            + ". Either hide them for this desk or show what they are for.", "vendor", null, quiet));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> decide(String suggestionId, String decision) {
        String tenant = tenantScope.currentTenantId();
        Map<String, Object> found = suggestions().stream().filter(s -> suggestionId.equals(s.get("id"))).findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", suggestionId);
        result.put("decision", decision);
        if (found != null && "accepted".equals(decision) && found.get("action") instanceof Map<?, ?> action) {
            if ("preset".equals(action.get("kind"))) {
                String valuesJson;
                try {
                    valuesJson = json.writeValueAsString(action.get("values"));
                } catch (Exception e) {
                    valuesJson = "{}";
                }
                // the same preset accepted twice is one preset
                DeskPreset existing = presets.findByTenantIdAndDeskAndFormOrderByCreatedAtDesc(tenant, str(action.get("desk")), str(action.get("form")))
                        .stream().filter(x -> valuesJson.equals(x.getValuesJson())).findFirst().orElse(null);
                if (existing != null) {
                    result.put("preset", presetView(existing));
                    return result;
                }
                DeskPreset p = new DeskPreset();
                p.setId(UUID.randomUUID().toString());
                p.setTenantId(tenant);
                p.setDesk(str(action.get("desk")));
                p.setForm(str(action.get("form")));
                p.setName("Preset · " + String.join(", ", ((Map<?, ?>) action.get("values")).values().stream().map(String::valueOf).limit(3).toList()));
                p.setValuesJson(valuesJson);
                p.setCreatedAt(OffsetDateTime.now());
                presets.save(p);
                result.put("preset", presetView(p));
            } else {
                result.put("action", action); // the desk executes it with the user's own token
            }
        }
        DeskDecision d = new DeskDecision();
        d.setId(UUID.randomUUID().toString());
        d.setTenantId(tenant);
        d.setSuggestionId(suggestionId);
        d.setDecision(decision);
        d.setDecidedAt(OffsetDateTime.now());
        decisions.save(d);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> presets(String desk, String form) {
        String tenant = tenantScope.currentTenantId();
        List<DeskPreset> rows = form == null || form.isBlank()
                ? presets.findByTenantIdOrderByCreatedAtDesc(tenant)
                : presets.findByTenantIdAndDeskAndFormOrderByCreatedAtDesc(tenant, desk == null ? "console" : desk, form);
        return rows.stream().map(this::presetView).toList();
    }

    /** Vendor feed material: counts only. No hashes, no values, no tenant name — the caller adds nothing either. */
    @Transactional(readOnly = true)
    public Map<String, Object> export(int days) {
        Map<String, Object> f = friction(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("events", f.get("events"));
        out.put("activeStaff", f.get("activeStaff"));
        out.put("abandonedForms", ((List<Map<String, Object>>) f.get("abandonedForms")).stream()
                .map(x -> Map.of("form", x.get("form"), "count", x.get("count"), "stopField", x.getOrDefault("stopField", ""))).toList());
        out.put("repeatedForms", ((List<Map<String, Object>>) f.get("repeatedForms")).stream()
                .map(x -> Map.of("form", x.get("form"), "count", x.get("count"), "sharedFields", ((Map<?, ?>) x.get("commonValues")).keySet())).toList());
        out.put("emptySearchCount", ((List<?>) f.get("emptySearches")).size());
        out.put("copilotRewrites", f.get("copilotRewrites"));
        out.put("unusedFeatures", f.get("unusedFeatures"));
        return out;
    }

    /* ------------------------------------------------------------------ analysis */

    private static final class Analysis {
        List<Map<String, Object>> abandoned = new ArrayList<>();
        List<Map<String, Object>> repeated = new ArrayList<>();
        List<Map<String, Object>> emptySearches = new ArrayList<>();
        List<Map<String, Object>> rewrites = new ArrayList<>();
        List<Map<String, Object>> noHoldout = new ArrayList<>();
        List<String> unused = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Analysis analyse(List<DeskEvent> all) {
        Analysis a = new Analysis();
        // sessions: form starts, submits, last field
        Map<String, Map<String, Object>> starts = new LinkedHashMap<>(); // session|form -> {form, lastField, submitted}
        Map<String, List<Map<String, Object>>> submitsByActorForm = new LinkedHashMap<>();
        Map<String, Integer> emptyQueries = new TreeMap<>();
        Map<String, Integer> rewriteByForm = new TreeMap<>();
        Set<String> opened = new HashSet<>();
        List<String> tabs = List.of();
        for (DeskEvent e : all) {
            Map<String, Object> p = props(e);
            String target = e.getTarget() == null ? "" : e.getTarget();
            String key = e.getSessionId() + "|" + target;
            switch (e.getEvent()) {
                case "desk.tabs" -> { if (p.get("tabs") instanceof List<?> l) tabs = l.stream().map(String::valueOf).toList(); }
                case "tab.open" -> opened.add(target);
                case "form.start" -> starts.putIfAbsent(key, new HashMap<>(Map.of("form", target, "submitted", false)));
                case "form.field" -> { Map<String, Object> s = starts.get(key); if (s != null) s.put("lastField", str(p.get("field"))); }
                case "form.submit" -> {
                    Map<String, Object> s = starts.get(key);
                    if (s != null) s.put("submitted", true);
                    Map<String, Object> values = p.get("values") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                    submitsByActorForm.computeIfAbsent(e.getActorHash() + "|" + e.getDesk() + "|" + target, k -> new ArrayList<>()).add(values);
                    if ("journeys".equals(target) && values.containsKey("holdoutPercent")) {
                        String h = String.valueOf(values.get("holdoutPercent")).trim();
                        String jid = str(p.get("createdId"));
                        if ((h.isEmpty() || "0".equals(h) || "0.0".equals(h)) && jid != null && !jid.isBlank()) {
                            a.noHoldout.add(Map.of("journeyId", jid, "name", str(p.getOrDefault("createdName", "journey"))));
                        }
                    }
                }
                case "search.empty" -> emptyQueries.merge(trim(str(p.getOrDefault("query", "")), 60), 1, Integer::sum);
                case "copilot.draft" -> {
                    double ratio = p.get("editRatio") instanceof Number n ? n.doubleValue() : 0;
                    if (ratio >= 0.5) rewriteByForm.merge(target, 1, Integer::sum);
                }
                default -> { }
            }
        }
        // abandoned: started, never submitted in that session
        Map<String, int[]> abandonedByForm = new TreeMap<>();
        Map<String, Map<String, Integer>> stopFields = new HashMap<>();
        for (Map<String, Object> s : starts.values()) {
            if (Boolean.TRUE.equals(s.get("submitted"))) continue;
            String form = str(s.get("form"));
            abandonedByForm.computeIfAbsent(form, k -> new int[1])[0]++;
            String lf = str(s.get("lastField"));
            if (lf != null) stopFields.computeIfAbsent(form, k -> new HashMap<>()).merge(lf, 1, Integer::sum);
        }
        for (Map.Entry<String, int[]> en : abandonedByForm.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("form", en.getKey());
            row.put("count", en.getValue()[0]);
            Map<String, Integer> sf = stopFields.get(en.getKey());
            if (sf != null) row.put("stopField", sf.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(""));
            a.abandoned.add(row);
        }
        // repeated: same actor, same form, 3+ submits sharing values
        for (Map.Entry<String, List<Map<String, Object>>> en : submitsByActorForm.entrySet()) {
            List<Map<String, Object>> subs = en.getValue();
            if (subs.size() < 3) continue;
            Map<String, Object> common = new TreeMap<>();
            for (Map.Entry<String, Object> kv : subs.get(0).entrySet()) {
                String k = kv.getKey();
                String v = String.valueOf(kv.getValue());
                if (PRESET_IGNORE.contains(k) || v.isBlank() || "·".equals(v)) continue;
                if (subs.stream().allMatch(s -> v.equals(String.valueOf(s.get(k))))) common.put(k, v);
            }
            String[] parts = en.getKey().split("\\|", 3);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("desk", parts[1]);
            row.put("form", parts[2]);
            row.put("count", subs.size());
            row.put("commonValues", common);
            a.repeated.add(row);
        }
        emptyQueries.forEach((q, c) -> { if (!q.isBlank()) a.emptySearches.add(Map.of("query", q, "count", c)); });
        rewriteByForm.forEach((f, c) -> a.rewrites.add(Map.of("form", f, "count", c)));
        a.unused = tabs.stream().filter(t -> !opened.contains(t)).toList();
        return a;
    }

    /* ------------------------------------------------------------------ helpers */

    private Map<String, Object> suggestion(String kind, String target, String title, String evidence, String audience,
            Map<String, Object> action, Set<String> quiet) {
        String id = kind + "-" + sha(kind + "|" + target).substring(0, 12);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("kind", kind);
        s.put("target", target);
        s.put("title", title);
        s.put("evidence", evidence);
        s.put("audience", audience);
        if (action != null) s.put("action", action);
        s.put("quiet", quiet.contains(id));
        return s;
    }

    private Map<String, Object> presetView(DeskPreset p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", p.getId());
        v.put("desk", p.getDesk());
        v.put("form", p.getForm());
        v.put("name", p.getName());
        try {
            v.put("values", json.readValue(p.getValuesJson(), new TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            v.put("values", Map.of());
        }
        v.put("createdAt", p.getCreatedAt());
        return v;
    }

    private Map<String, Object> props(DeskEvent e) {
        try {
            return e.getProps() == null ? Map.of() : json.readValue(e.getProps(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String actorHash(String tenant) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String subject = auth == null ? "anonymous" : auth.getName();
        return sha(tenant + ":" + subject).substring(0, 32);
    }

    private static String sha(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : d.digest(s.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
