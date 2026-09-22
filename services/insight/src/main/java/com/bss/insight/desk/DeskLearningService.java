package com.bss.insight.desk;

import com.bss.insight.dto.DecisionInput;
import com.bss.insight.dto.DeskEventInput;
import com.bss.insight.dto.DeskExport;
import com.bss.insight.dto.DeskIngestReceipt;
import com.bss.insight.dto.DeskPresetView;
import com.bss.insight.dto.DeskSuggestion;
import com.bss.insight.dto.FrictionReport;
import com.bss.insight.dto.SuggestedAction;
import com.bss.insight.dto.SuggestionDecision;
import com.bss.insight.entity.DeskDecision;
import com.bss.insight.entity.DeskEvent;
import com.bss.insight.entity.DeskPreset;
import com.bss.insight.repository.DeskDecisionRepository;
import com.bss.insight.repository.DeskEventRepository;
import com.bss.insight.repository.DeskPresetRepository;
import com.bss.insight.security.TenantRegistry;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeskLearningService.class);

    private static final Set<String> PRESET_IGNORE = Set.of("name", "id", "description", "subject", "content",
            "steps", "code", "email", "phone", "msisdn", "href");
    private final DeskEventRepository events;
    private final DeskPresetRepository presets;
    private final DeskDecisionRepository decisions;
    private final TenantScope tenantScope;
    private final TenantRegistry registry;
    private final ObjectMapper json;

    private final com.bss.insight.decision.DecisionLogService decisionLog;

    public DeskLearningService(DeskEventRepository events, DeskPresetRepository presets,
            DeskDecisionRepository decisions, TenantScope tenantScope, TenantRegistry registry, ObjectMapper json,
            com.bss.insight.decision.DecisionLogService decisionLog) {
        this.decisionLog = decisionLog;
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
    public DeskIngestReceipt ingest(List<DeskEventInput> batch) {
        if (!enabled()) {
            return new DeskIngestReceipt(0, false);
        }
        String tenant = tenantScope.currentTenantId();
        String actor = actorHash(tenant);
        int n = 0;
        for (DeskEventInput e : batch == null ? List.<DeskEventInput>of() : batch) {
            String event = e.event();
            if (event == null || event.isBlank()) {
                continue;
            }
            DeskEvent d = new DeskEvent();
            d.setId(UUID.randomUUID().toString());
            d.setTenantId(tenant);
            d.setActorHash(actor);
            d.setSessionId(trim(e.session(), 64));
            d.setDesk(trim(e.desk() == null ? "console" : e.desk(), 32));
            d.setEvent(trim(event, 32));
            d.setTarget(trim(e.target(), 128));
            JsonNode props = e.props();
            try {
                d.setProps(props == null || props.isNull() ? null
                        : trim(json.writeValueAsString(scrub(json.convertValue(props, Object.class))), 4000));
            } catch (Exception ex) {
                d.setProps(null);
            }
            d.setOccurredAt(OffsetDateTime.now());
            events.save(d);
            n++;
        }
        return new DeskIngestReceipt(n, true);
    }

    /** No free text that could carry a customer: values are kept only for short, enumerable fields. */
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
    public FrictionReport friction(int days) {
        String tenant = tenantScope.currentTenantId();
        List<DeskEvent> all = events.findByTenantIdAndOccurredAtAfterOrderByOccurredAtAsc(tenant,
                OffsetDateTime.now().minusDays(Math.max(1, Math.min(days, 90))));
        Analysis a = analyse(all);
        return new FrictionReport(enabled(), days, all.size(),
                all.stream().map(DeskEvent::getActorHash).collect(Collectors.toSet()).size(),
                a.abandoned, a.repeated, a.emptySearches, a.rewrites, a.unused, a.noHoldout);
    }

    /* ------------------------------------------------------------------ suggestions */

    @Transactional(readOnly = true)
    public List<DeskSuggestion> suggestions() {
        String tenant = tenantScope.currentTenantId();
        List<DeskEvent> all = events.findByTenantIdAndOccurredAtAfterOrderByOccurredAtAsc(tenant, OffsetDateTime.now().minusDays(7));
        Analysis a = analyse(all);
        Set<String> quiet = decisions.findByTenantIdAndDecidedAtAfter(tenant, OffsetDateTime.now().minusDays(30))
                .stream().map(DeskDecision::getSuggestionId).collect(Collectors.toSet());
        List<DeskSuggestion> out = new ArrayList<>();
        for (FrictionReport.RepeatedForm r : a.repeated) {
            Map<String, String> common = r.commonValues();
            if (common == null || common.isEmpty()) {
                continue;
            }
            DeskSuggestion s = suggestion("preset", r.form(), "Save a preset for the " + r.form() + " form",
                    r.count() + " submissions in 7 days shared the same " + common.keySet() + " — one click would prefill them.",
                    "operator", SuggestedAction.Preset.of(r.desk(), r.form(), common), quiet);
            // structured facts beside the sentence, so a desk can say them in its own words (page titles, field labels)
            out.add(s.preset(r.form(), new ArrayList<>(common.keySet()), r.count()));
        }
        for (FrictionReport.JourneyWithoutHoldout j : a.noHoldout) {
            out.add(suggestion("holdout", j.journeyId(), "Add a holdout to the journey \"" + j.name() + "\"",
                    "Created without a control group, so its lift cannot be measured. 10 % held out keeps the claim honest.",
                    "operator", SuggestedAction.Http.of("PATCH",
                            "/tmf-api/campaignManagement/v4/journey/" + j.journeyId(),
                            json.createObjectNode().put("holdoutPercent", 10)), quiet));
        }
        for (FrictionReport.AbandonedForm ab : a.abandoned) {
            if (ab.count() >= 2) {
                String stop = ab.stopField() == null ? "?" : ab.stopField();
                DeskSuggestion s = suggestion("abandon", ab.form(), "People start the " + ab.form() + " form and leave",
                        ab.count() + " abandoned starts; most stop at \"" + stop + "\". "
                                + "A default, a hint or a preset there would help.", "vendor", null, quiet);
                out.add(s.abandon(ab.form(), ab.stopField() == null ? "" : ab.stopField(), ab.count()));
            }
        }
        for (FrictionReport.EmptySearch s : a.emptySearches) {
            if (s.count() >= 2) {
                out.add(suggestion("search", s.query(), "Searches for \"" + s.query() + "\" find nothing",
                        s.count() + " times this week. A knowledge article, an alias or a filter would answer it.",
                        "operator", null, quiet));
            }
        }
        for (FrictionReport.CopilotRewrite rw : a.rewrites) {
            if (rw.count() >= 2) {
                DeskSuggestion s = suggestion("rewrite", rw.form(), "Copilot drafts for " + rw.form() + " are rewritten before use",
                        rw.count() + " drafts changed by more than half. The prompt or its defaults are off for this tenant.",
                        "vendor", null, quiet);
                out.add(s.rewrite(rw.form(), rw.count()));
            }
        }
        // "never opened" only means something once the desk has been used: a week of
        // one person poking at a few tabs says nothing about the other fifty pages
        int people = all.stream().map(DeskEvent::getActorHash).collect(Collectors.toSet()).size();
        if (!a.unused.isEmpty() && all.size() >= UNUSED_MIN_ACTIONS && a.opened >= UNUSED_MIN_OPENED && people >= UNUSED_MIN_PEOPLE) {
            DeskSuggestion s = suggestion("unused", "features", a.unused.size() + " pages nobody opened this week",
                    "In " + all.size() + " desk actions by " + people + (people == 1 ? " person" : " people") + ", "
                            + a.opened + " pages were used and these never: "
                            + String.join(", ", a.unused.stream().limit(8).toList()) + (a.unused.size() > 8 ? ", …" : "")
                            + ". Open one to see what it is for, or tell us if this desk never needs it.", "vendor", null, quiet);
            out.add(s.unused(a.unused, a.opened, all.size(), people));
        }
        return out;
    }

    /** The desk must have seen this much use before "never opened" is a suggestion rather than an echo of a quiet week. */
    static final int UNUSED_MIN_ACTIONS = 50;
    static final int UNUSED_MIN_OPENED = 5;
    /** "nobody" needs more than one body: one person's week says what they did, not what the desk needs. */
    static final int UNUSED_MIN_PEOPLE = 2;

    @Transactional
    public SuggestionDecision decide(String suggestionId, String decision) {
        String tenant = tenantScope.currentTenantId();
        DeskSuggestion found = suggestions().stream().filter(s -> suggestionId.equals(s.id())).findFirst().orElse(null);
        DeskPresetView preset = null;
        SuggestedAction action = null;
        if (found != null && "accepted".equals(decision) && found.action() instanceof SuggestedAction.Preset p) {
            String valuesJson;
            try {
                valuesJson = json.writeValueAsString(p.values());
            } catch (Exception e) {
                valuesJson = "{}";
            }
            // the same preset accepted twice is one preset
            final String wanted = valuesJson;
            DeskPreset existing = presets.findByTenantIdAndDeskAndFormOrderByCreatedAtDesc(tenant, p.desk(), p.form())
                    .stream().filter(x -> wanted.equals(x.getValuesJson())).findFirst().orElse(null);
            if (existing != null) {
                return new SuggestionDecision(suggestionId, decision, presetView(existing), null);
            }
            DeskPreset saved = new DeskPreset();
            saved.setId(UUID.randomUUID().toString());
            saved.setTenantId(tenant);
            saved.setDesk(p.desk());
            saved.setForm(p.form());
            saved.setName("Preset · " + String.join(", ", p.values().values().stream().limit(3).toList()));
            saved.setValuesJson(valuesJson);
            saved.setCreatedAt(OffsetDateTime.now());
            presets.save(saved);
            preset = presetView(saved);
        } else if (found != null && "accepted".equals(decision) && found.action() instanceof SuggestedAction.Http h) {
            action = h; // the desk executes it with the user's own token
        }
        DeskDecision d = new DeskDecision();
        d.setId(UUID.randomUUID().toString());
        d.setTenantId(tenant);
        d.setSuggestionId(suggestionId);
        d.setDecision(decision);
        d.setDecidedAt(OffsetDateTime.now());
        decisions.save(d);
        // the human's verdict is the suggestion's outcome
        decisionLog.outcome(tenant, "desk-" + suggestionId, decision, null, null);
        return new SuggestionDecision(suggestionId, decision, preset, action);
    }

    @Transactional(readOnly = true)
    public List<DeskPresetView> presets(String desk, String form) {
        String tenant = tenantScope.currentTenantId();
        List<DeskPreset> rows = form == null || form.isBlank()
                ? presets.findByTenantIdOrderByCreatedAtDesc(tenant)
                : presets.findByTenantIdAndDeskAndFormOrderByCreatedAtDesc(tenant, desk == null ? "console" : desk, form);
        return rows.stream().map(this::presetView).toList();
    }

    /** Vendor feed material: counts only. No hashes, no values, no tenant name — the caller adds nothing either. */
    @Transactional(readOnly = true)
    public DeskExport export(int days) {
        FrictionReport f = friction(days);
        return new DeskExport(days, f.events(), f.activeStaff(),
                f.abandonedForms().stream().map(x -> new DeskExport.Abandoned(x.form(), x.count(),
                        x.stopField() == null ? "" : x.stopField())).toList(),
                f.repeatedForms().stream().map(x -> new DeskExport.Repeated(x.form(), x.count(),
                        x.commonValues().keySet())).toList(),
                f.emptySearches().size(), f.copilotRewrites(), f.unusedFeatures());
    }

    /* ------------------------------------------------------------------ analysis */

    private static final class Analysis {
        List<FrictionReport.AbandonedForm> abandoned = new ArrayList<>();
        List<FrictionReport.RepeatedForm> repeated = new ArrayList<>();
        List<FrictionReport.EmptySearch> emptySearches = new ArrayList<>();
        List<FrictionReport.CopilotRewrite> rewrites = new ArrayList<>();
        List<FrictionReport.JourneyWithoutHoldout> noHoldout = new ArrayList<>();
        List<String> unused = new ArrayList<>();
        int opened;
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
                            a.noHoldout.add(new FrictionReport.JourneyWithoutHoldout(jid, str(p.getOrDefault("createdName", "journey"))));
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
            Map<String, Integer> sf = stopFields.get(en.getKey());
            String stop = sf == null ? null
                    : sf.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            a.abandoned.add(new FrictionReport.AbandonedForm(en.getKey(), en.getValue()[0], stop));
        }
        // repeated: same actor, same form, 3+ submits sharing values
        for (Map.Entry<String, List<Map<String, Object>>> en : submitsByActorForm.entrySet()) {
            List<Map<String, Object>> subs = en.getValue();
            if (subs.size() < 3) continue;
            Map<String, String> common = new TreeMap<>();
            for (Map.Entry<String, Object> kv : subs.get(0).entrySet()) {
                String k = kv.getKey();
                String v = String.valueOf(kv.getValue());
                if (PRESET_IGNORE.contains(k) || v.isBlank() || "·".equals(v)) continue;
                if (subs.stream().allMatch(s -> v.equals(String.valueOf(s.get(k))))) common.put(k, v);
            }
            String[] parts = en.getKey().split("\\|", 3);
            a.repeated.add(new FrictionReport.RepeatedForm(parts[1], parts[2], subs.size(), common));
        }
        emptyQueries.forEach((q, c) -> { if (!q.isBlank()) a.emptySearches.add(new FrictionReport.EmptySearch(q, c)); });
        rewriteByForm.forEach((f, c) -> a.rewrites.add(new FrictionReport.CopilotRewrite(f, c)));
        a.unused = tabs.stream().filter(t -> !opened.contains(t)).toList();
        a.opened = opened.size();
        return a;
    }

    /* ------------------------------------------------------------------ helpers */

    private DeskSuggestion suggestion(String kind, String target, String title, String evidence, String audience,
            SuggestedAction action, Set<String> quiet) {
        String id = kind + "-" + sha(kind + "|" + target).substring(0, 12);
        // the suggestion IS a decision of the BSS (desk.suggestion): logged once
        // per content-derived id, so the accept/dismiss can be attributed later
        String decisionId = "desk-" + id;
        try {
            ObjectNode context = json.createObjectNode();
            context.put("target", target);
            context.put("audience", audience);
            ObjectNode evidenceNode = json.createObjectNode();
            evidenceNode.put("title", title);
            JsonNode kinds = json.valueToTree(SUGGESTION_KINDS);
            decisionLog.record(tenantScope.currentTenantId(), new DecisionInput(decisionId, "desk.suggestion", "desk", target,
                    kinds, kinds, json.createArrayNode(), kind, null, "desk-rules", "1", evidence, context, evidenceNode,
                    "medium", false, "insight", OffsetDateTime.now().toString(), null));
        } catch (RuntimeException e) {
            log.debug("desk suggestion {} not logged as a decision: {}", id, e.getMessage());
        }
        return new DeskSuggestion(id, kind, target, title, evidence, audience, action, quiet.contains(id), decisionId,
                null, null, null, null, null, null, null, null);
    }

    /** Everything the desk rules can suggest — the eligible set of desk.suggestion. */
    private static final List<String> SUGGESTION_KINDS = List.of("preset", "holdout", "abandon", "search", "rewrite", "unused");

    private DeskPresetView presetView(DeskPreset p) {
        JsonNode values;
        try {
            values = json.readTree(p.getValuesJson());
        } catch (Exception e) {
            values = json.createObjectNode();
        }
        return new DeskPresetView(p.getId(), p.getDesk(), p.getForm(), p.getName(), values, p.getCreatedAt());
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
