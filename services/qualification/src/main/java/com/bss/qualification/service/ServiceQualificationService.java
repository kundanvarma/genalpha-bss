package com.bss.qualification.service;

import com.bss.qualification.entity.CoverageMap;
import com.bss.qualification.entity.ServiceQualification;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.CoverageMapRepository;
import com.bss.qualification.repository.ServiceQualificationRepository;
import com.bss.qualification.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * TMF645, the house way: TMF679 next door answers the COMMERCIAL question
 * (may this offering be sold here); this service answers the TECHNICAL one
 * (what can the network deliver here, at what bandwidth) from the coverage
 * map — the operator's footprint as data. The 645 signature behavior is the
 * ALTERNATIVE: asked for fiber where there is none, the answer is not just
 * "no" but "no — and here is the best technology this address CAN have".
 * Longest matching prefix wins per technology; an empty prefix is the
 * everywhere-fallback. Checks are PERSISTED: a qualification is a fact a
 * channel may need to show again.
 */
@Service
public class ServiceQualificationService {

    public static final String QUALIFIED = "qualified";
    public static final String UNQUALIFIED = "unqualified";

    private final CoverageMapRepository coverage;
    private final ServiceQualificationRepository qualifications;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public ServiceQualificationService(CoverageMapRepository coverage,
            ServiceQualificationRepository qualifications, TenantScope tenantScope,
            ObjectMapper objectMapper) {
        this.coverage = coverage;
        this.qualifications = qualifications;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    /* =====================================================================
     * queryServiceQualification: "what CAN you deliver here?" — every
     * technology whose footprint covers the postcode, best row each.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public Map<String, Object> query(Map<String, Object> request) {
        Map<String, Object> place = placeOf(request);
        String postCode = postCodeOf(place);
        List<Map<String, Object>> items = new ArrayList<>();
        int n = 1;
        for (CoverageMap row : bestRowsAt(postCode)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(n++));
            item.put("state", "done");
            item.put("qualificationItemResult", QUALIFIED);
            item.put("service", serviceView(row));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", UUID.randomUUID().toString());
        out.put("state", "done");
        out.put("instantSync", true);
        out.put("searchCriteria", Map.of("place", place));
        out.put("serviceQualificationItem", items);
        out.put("@type", "QueryServiceQualification");
        return out;
    }

    /* =====================================================================
     * checkServiceQualification: "can you deliver THIS here?" — verdict
     * per requested technology, alternative proposed on refusal, kept.
     * ===================================================================== */

    @Transactional
    public Map<String, Object> check(Map<String, Object> request) {
        List<Map<String, Object>> items = listOf(request.get("serviceQualificationItem"));
        if (items.isEmpty()) {
            throw new BadRequestException("serviceQualificationItem is required");
        }
        Map<String, Object> topPlace = placeOf(request);
        List<Map<String, Object>> outItems = new ArrayList<>();
        boolean allQualified = true;
        int n = 1;
        for (Map<String, Object> item : items) {
            Map<String, Object> place = item.containsKey("place") || serviceOf(item).containsKey("place")
                    ? placeOf(item) : topPlace;
            Map<String, Object> outItem = checkOne(item, place);
            outItem.put("id", item.get("id") == null ? String.valueOf(n) : item.get("id"));
            allQualified &= QUALIFIED.equals(outItem.get("qualificationItemResult"));
            outItems.add(outItem);
            n++;
        }

        ServiceQualification row = new ServiceQualification();
        String id = UUID.randomUUID().toString();
        row.setId(id);
        row.setHref("/tmf-api/serviceQualificationManagement/v4/checkServiceQualification/" + id);
        row.setTenantId(tenantScope.currentTenantId());
        row.setPlaceJson(writeJson(topPlace));
        row.setResultJson(writeJson(outItems));
        row.setState("done");
        row.setQualificationResult(allQualified ? QUALIFIED : UNQUALIFIED);
        row.setCreatedAt(OffsetDateTime.now());
        qualifications.save(row);

        return checkView(row, outItems);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findCheck(String id) {
        ServiceQualification row = qualifications
                .findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CheckServiceQualification", id));
        return checkView(row, readJson(row.getResultJson()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listChecks() {
        return qualifications.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(r -> checkView(r, readJson(r.getResultJson()))).toList();
    }

    /* ---------- the verdict ---------- */

    private Map<String, Object> checkOne(Map<String, Object> item, Map<String, Object> place) {
        String postCode = postCodeOf(place);
        String technology = requestedTechnology(item);
        Map<String, Object> outItem = new LinkedHashMap<>();
        outItem.put("state", "done");
        if (technology == null) {
            outItem.put("qualificationItemResult", UNQUALIFIED);
            outItem.put("eligibilityUnavailabilityReason", List.of(Map.of(
                    "code", "missingService",
                    "label", "qualification item without a requested service"
                            + " (serviceSpecification.name or a 'technology' characteristic)")));
            return outItem;
        }
        Integer minDown = requestedMinDown(item);
        Optional<CoverageMap> best = bestRowAt(postCode, technology);
        boolean bandwidthOk = best.isPresent()
                && (minDown == null || best.get().getMaxDownMbps() >= minDown);
        if (best.isPresent() && bandwidthOk) {
            outItem.put("qualificationItemResult", QUALIFIED);
            outItem.put("service", serviceView(best.get()));
            return outItem;
        }
        outItem.put("qualificationItemResult", UNQUALIFIED);
        String label = best.isEmpty()
                ? technology + " is not available at postcode "
                        + (postCode.isEmpty() ? "(none given)" : postCode)
                : technology + " at postcode " + postCode + " delivers at most "
                        + best.get().getMaxDownMbps() + " Mbps, below the requested " + minDown;
        outItem.put("eligibilityUnavailabilityReason", List.of(Map.of(
                "code", best.isEmpty() ? "noCoverage" : "insufficientBandwidth", "label", label)));
        // the TMF645 signature: never a bare no — the best this address CAN have
        bestRowsAt(postCode).stream()
                .filter(r -> !r.getTechnology().equals(technology) || !bandwidthOk)
                .max(Comparator.comparing(CoverageMap::getMaxDownMbps))
                .ifPresent(alt -> outItem.put("alternateServiceProposal", List.of(Map.of(
                        "id", "alt-1",
                        "alternateService", serviceView(alt),
                        "@type", "AlternateServiceProposal"))));
        return outItem;
    }

    /** Best row per technology at this postcode: longest matching prefix. */
    private List<CoverageMap> bestRowsAt(String postCode) {
        Map<String, CoverageMap> best = new LinkedHashMap<>();
        for (CoverageMap row : coverage.findByTenantId(tenantScope.currentTenantId())) {
            if (!matches(postCode, row)) {
                continue;
            }
            CoverageMap current = best.get(row.getTechnology());
            if (current == null
                    || row.getPostcodePrefix().length() > current.getPostcodePrefix().length()) {
                best.put(row.getTechnology(), row);
            }
        }
        return new ArrayList<>(best.values());
    }

    private Optional<CoverageMap> bestRowAt(String postCode, String technology) {
        return bestRowsAt(postCode).stream()
                .filter(r -> r.getTechnology().equalsIgnoreCase(technology))
                .findFirst();
    }

    private static boolean matches(String postCode, CoverageMap row) {
        String prefix = row.getPostcodePrefix() == null ? ""
                : row.getPostcodePrefix().replaceAll("\\s", "");
        return prefix.isEmpty() || postCode.startsWith(prefix);
    }

    /* ---------- shapes ---------- */

    private Map<String, Object> serviceView(CoverageMap row) {
        List<Map<String, Object>> characteristics = new ArrayList<>();
        characteristics.add(Map.of("name", "technology", "value", row.getTechnology()));
        characteristics.add(Map.of("name", "maxDownstreamMbps", "value", row.getMaxDownMbps()));
        if (row.getMaxUpMbps() != null) {
            characteristics.add(Map.of("name", "maxUpstreamMbps", "value", row.getMaxUpMbps()));
        }
        // open access: name the fibre owner + the layer, when this footprint is
        // served by a wholesaler rather than our own network
        if (row.getAccessOwner() != null) {
            characteristics.add(Map.of("name", "accessOwner", "value", row.getAccessOwner()));
        }
        if (row.getAccessLayer() != null) {
            characteristics.add(Map.of("name", "accessLayer", "value", row.getAccessLayer()));
        }
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("serviceSpecification", Map.of(
                "name", "broadband-" + row.getTechnology(), "@referredType", "ServiceSpecification"));
        service.put("serviceCharacteristic", characteristics);
        service.put("@type", "Service");
        return service;
    }

    /* =====================================================================
     * queryAccessOptions: the WHOLESALE question — "which access owners can
     * serve this address, at what layer and bandwidth?" Open access turns one
     * footprint into a shortlist of suppliers a retail ISP can buy from. Only
     * owner-served rows (access_owner set) are options; our own network is not
     * a wholesale option. Best bandwidth per (owner, layer), longest prefix.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public Map<String, Object> accessOptions(Map<String, Object> request) {
        Map<String, Object> place = placeOf(request);
        String postCode = postCodeOf(place);
        String technology = Optional.ofNullable(requestedTechnology(request))
                .or(() -> Optional.ofNullable(technologyCriterion(request)))
                .orElse("fiber");
        Map<String, CoverageMap> best = new LinkedHashMap<>();
        for (CoverageMap row : coverage.findByTenantId(tenantScope.currentTenantId())) {
            if (row.getAccessOwner() == null || !matches(postCode, row)
                    || !row.getTechnology().equalsIgnoreCase(technology)) {
                continue;
            }
            String key = row.getAccessOwner() + "|" + row.getAccessLayer();
            CoverageMap current = best.get(key);
            if (current == null
                    || row.getPostcodePrefix().length() > current.getPostcodePrefix().length()) {
                best.put(key, row);
            }
        }
        List<Map<String, Object>> options = new ArrayList<>();
        for (CoverageMap row : best.values()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("accessOwner", row.getAccessOwner());
            option.put("accessLayer", row.getAccessLayer());
            option.put("technology", row.getTechnology());
            option.put("maxDownMbps", row.getMaxDownMbps());
            if (row.getMaxUpMbps() != null) {
                option.put("maxUpMbps", row.getMaxUpMbps());
            }
            option.put("@type", "WholesaleAccessOption");
            options.add(option);
        }
        options.sort(Comparator.comparingInt((Map<String, Object> o) ->
                (Integer) o.get("maxDownMbps")).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("place", place);
        out.put("technology", technology);
        out.put("accessOption", options);
        out.put("@type", "QueryAccessOptions");
        return out;
    }

    private static String technologyCriterion(Map<String, Object> request) {
        if (request.get("searchCriteria") instanceof Map<?, ?> c && c.get("technology") != null) {
            return String.valueOf(c.get("technology"));
        }
        return request.get("technology") == null ? null : String.valueOf(request.get("technology"));
    }

    private Map<String, Object> checkView(ServiceQualification row, List<Map<String, Object>> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("href", row.getHref());
        out.put("state", row.getState());
        out.put("qualificationResult", row.getQualificationResult());
        out.put("place", readJsonObject(row.getPlaceJson()));
        out.put("serviceQualificationItem", items);
        out.put("checkServiceQualificationDate", row.getCreatedAt());
        out.put("@type", "CheckServiceQualification");
        return out;
    }

    /* ---------- request parsing ---------- */

    /** The place, wherever the caller put it: searchCriteria.place, the
     * item's own place (list or object), the service's place, or top-level. */
    private static Map<String, Object> placeOf(Map<String, Object> request) {
        Object candidate = null;
        if (request.get("searchCriteria") instanceof Map<?, ?> criteria) {
            candidate = criteria.get("place");
        }
        if (candidate == null) {
            candidate = serviceOf(request).get("place");
        }
        if (candidate == null) {
            candidate = request.get("place");
        }
        if (candidate instanceof List<?> list && !list.isEmpty()) {
            candidate = list.get(0);
        }
        return candidate instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    private static String postCodeOf(Map<String, Object> place) {
        return place.get("postCode") == null ? ""
                : String.valueOf(place.get("postCode")).replaceAll("\\s", "");
    }

    private static Map<String, Object> serviceOf(Map<String, Object> item) {
        return item.get("service") instanceof Map<?, ?> service ? castMap(service) : Map.of();
    }

    private static String requestedTechnology(Map<String, Object> item) {
        Map<String, Object> service = serviceOf(item);
        for (Map<String, Object> c : listOf(service.get("serviceCharacteristic"))) {
            if ("technology".equals(c.get("name")) && c.get("value") != null) {
                return String.valueOf(c.get("value"));
            }
        }
        if (service.get("serviceSpecification") instanceof Map<?, ?> spec && spec.get("name") != null) {
            // "broadband-fiber" and plain "fiber" both name the technology
            return String.valueOf(spec.get("name")).replaceFirst("^broadband-", "");
        }
        return null;
    }

    private static Integer requestedMinDown(Map<String, Object> item) {
        for (Map<String, Object> c : listOf(serviceOf(item).get("serviceCharacteristic"))) {
            if ("minDownstreamMbps".equals(c.get("name")) && c.get("value") != null) {
                try {
                    return Integer.valueOf(String.valueOf(c.get("value")));
                } catch (NumberFormatException e) {
                    throw new BadRequestException("minDownstreamMbps must be a number");
                }
            }
        }
        return null;
    }

    /* ---------- plumbing ---------- */

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
            throw new IllegalStateException("stored qualification result is unreadable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored place is unreadable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }
}
