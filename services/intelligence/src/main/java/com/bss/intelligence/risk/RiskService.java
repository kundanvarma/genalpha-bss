package com.bss.intelligence.risk;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF696, the house way: the acquisition-side twin of the churn scorer.
 * The score is TRANSPARENT and additive — every signal contributes named
 * points, every signal's raw evidence rides in the assessment body, and
 * the total can be recomputed by hand from what the assessment itself
 * says. The engine reads only what the fleet's data actually knows:
 * unpaid bills, credit notes, order velocity, tenure — and, for an ORDER
 * assessment, the order's shape and the SESSION's verified identity
 * (supplied by the caller, who holds the token; a party has no persisted
 * "ever verified" flag and this face will not invent one). Failed
 * payments are deliberately absent: refusals never persist a row, so the
 * data cannot answer — and a face must not claim what the thing does not
 * know.
 */
@Service
public class RiskService {

    private static final String BASE = "/tmf-api/riskManagement/v4";

    private final BssApiClient bss;
    private final RiskAssessmentRepository assessments;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public RiskService(BssApiClient bss, RiskAssessmentRepository assessments,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.bss = bss;
        this.assessments = assessments;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> assessParty(Map<String, Object> request) {
        String partyId = partyIdOf(request);
        List<Map<String, Object>> signals = partySignals(partyId);
        return persist(RiskAssessment.PARTY, partyId, signals);
    }

    @Transactional
    public Map<String, Object> assessOrder(Map<String, Object> request) {
        String partyId = partyIdOf(request);
        List<Map<String, Object>> signals = partySignals(partyId);

        long totalQuantity = longOf(request.get("totalQuantity"), 0);
        if (totalQuantity >= 5) {
            signals.add(signal("bulkOrder", 10,
                    totalQuantity + " units in one order", Map.of("totalQuantity", totalQuantity)));
        }
        // BankID is the strongest anti-fraud signal the fleet has — a
        // verified SESSION reduces risk. Only the caller knows it.
        if (Boolean.TRUE.equals(request.get("verifiedIdentity"))) {
            signals.add(signal("verifiedSession", -20,
                    "the ordering session is BankID-verified", Map.of("verifiedIdentity", true)));
        }
        return persist(RiskAssessment.PRODUCT_ORDER, partyId, signals);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> find(String id) {
        RiskAssessment row = assessments.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no risk assessment '" + id + "'"));
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return assessments.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    /* ---------- the signals the data actually knows ---------- */

    private List<Map<String, Object>> partySignals(String partyId) {
        List<Map<String, Object>> signals = new ArrayList<>();

        List<Map<String, Object>> unpaid = bss.unpaidBills(partyId);
        if (!unpaid.isEmpty()) {
            BigDecimal due = unpaid.stream()
                    .map(b -> b.get("amountDue") instanceof Map<?, ?> a && a.get("value") != null
                            ? new BigDecimal(String.valueOf(a.get("value"))) : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int points = Math.min(unpaid.size() * 15, 30)
                    + (due.compareTo(new BigDecimal("200")) >= 0 ? 10 : 0);
            signals.add(signal("unpaidBills", points,
                    unpaid.size() + " unpaid bill(s), " + due + " due",
                    Map.of("count", unpaid.size(), "amountDue", due)));
        }

        List<Map<String, Object>> creditNotes = bss.creditNotesOf(partyId);
        if (!creditNotes.isEmpty()) {
            signals.add(signal("creditNotes", Math.min(creditNotes.size() * 5, 15),
                    creditNotes.size() + " credit note(s) issued",
                    Map.of("count", creditNotes.size())));
        }

        OffsetDateTime dayAgo = OffsetDateTime.now().minusHours(24);
        long recentOrders = bss.ordersOf(partyId).stream()
                .filter(o -> o.get("orderDate") != null)
                .filter(o -> {
                    try {
                        return OffsetDateTime.parse(String.valueOf(o.get("orderDate"))).isAfter(dayAgo);
                    } catch (RuntimeException e) {
                        return false;
                    }
                }).count();
        if (recentOrders > 1) {
            signals.add(signal("orderVelocity", (int) Math.min((recentOrders - 1) * 10, 30),
                    recentOrders + " orders in the last 24h",
                    Map.of("ordersLast24h", recentOrders)));
        }

        OffsetDateTime oldestRole = bss.partyRolesOf(partyId).stream()
                .map(r -> {
                    try {
                        return r.get("createdAt") == null ? null
                                : OffsetDateTime.parse(String.valueOf(r.get("createdAt")));
                    } catch (RuntimeException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .min(OffsetDateTime::compareTo).orElse(null);
        if (oldestRole != null) {
            long tenureDays = Duration.between(oldestRole, OffsetDateTime.now()).toDays();
            if (tenureDays < 7) {
                signals.add(signal("newAccount", 15,
                        "the oldest party role is " + tenureDays + " day(s) old",
                        Map.of("tenureDays", tenureDays)));
            } else if (tenureDays < 30) {
                signals.add(signal("youngAccount", 5,
                        "the oldest party role is " + tenureDays + " day(s) old",
                        Map.of("tenureDays", tenureDays)));
            }
        }
        return signals;
    }

    /* ---------- scoring + persistence ---------- */

    private Map<String, Object> persist(String kind, String partyId,
            List<Map<String, Object>> signals) {
        int score = Math.max(0, Math.min(100, signals.stream()
                .mapToInt(s -> (int) s.get("points")).sum()));
        String level = score < 30 ? "low" : score < 60 ? "medium" : "high";

        RiskAssessment row = new RiskAssessment();
        String id = UUID.randomUUID().toString();
        row.setId(id);
        row.setTenantId(tenantScope.currentTenantId());
        row.setPartyId(partyId);
        row.setKind(kind);
        row.setScore(score);
        row.setRiskLevel(level);
        row.setResultJson(writeJson(signals));
        row.setCreatedAt(OffsetDateTime.now());
        assessments.save(row);
        return view(row);
    }

    private static Map<String, Object> signal(String name, int points, String label,
            Map<String, Object> evidence) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("points", points);
        s.put("label", label);
        s.put("evidence", evidence);
        return s;
    }

    private Map<String, Object> view(RiskAssessment row) {
        String resource = RiskAssessment.PARTY.equals(row.getKind())
                ? "partyRiskAssessment" : "productOrderRiskAssessment";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("href", BASE + "/" + resource + "/" + row.getId());
        out.put("status", "done");
        out.put("relatedParty", List.of(Map.of("id", row.getPartyId(), "role", "customer")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overallScore", row.getScore());
        result.put("riskLevel", row.getRiskLevel());
        result.put("signal", readJson(row.getResultJson()));
        out.put("riskAssessmentResult", result);
        out.put("assessedAt", row.getCreatedAt());
        out.put("@type", RiskAssessment.PARTY.equals(row.getKind())
                ? "PartyRiskAssessment" : "ProductOrderRiskAssessment");
        return out;
    }

    /* ---------- plumbing ---------- */

    private static String partyIdOf(Map<String, Object> request) {
        Object related = request.get("relatedParty");
        if (related instanceof List<?> list && !list.isEmpty()) {
            related = list.get(0);
        }
        if (related instanceof Map<?, ?> ref && ref.get("id") != null) {
            return String.valueOf(ref.get("id"));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relatedParty.id is required");
    }

    private static long longOf(Object v, long dflt) {
        try {
            return v == null ? dflt : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJson(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored assessment result is unreadable", e);
        }
    }
}
