package com.bss.qualification.service;

import com.bss.qualification.dto.AccessOption;
import com.bss.qualification.dto.AccessOptionsResult;
import com.bss.qualification.dto.AlternateServiceProposal;
import com.bss.qualification.dto.Characteristic;
import com.bss.qualification.dto.CheckItemView;
import com.bss.qualification.dto.CheckServiceQualificationView;
import com.bss.qualification.dto.QueryItemView;
import com.bss.qualification.dto.QueryServiceQualificationResult;
import com.bss.qualification.dto.Reason;
import com.bss.qualification.dto.SearchCriteria;
import com.bss.qualification.dto.ServiceQualificationRequest;
import com.bss.qualification.dto.ServiceView;
import com.bss.qualification.entity.CoverageMap;
import com.bss.qualification.entity.ServiceQualification;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.CoverageMapRepository;
import com.bss.qualification.repository.ServiceQualificationRepository;
import com.bss.qualification.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

    private static final TypeReference<List<CheckItemView>> STORED_ITEMS =
            new TypeReference<>() {
            };
    private static final TypeReference<Map<String, Object>> STORED_PLACE =
            new TypeReference<>() {
            };

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
    public QueryServiceQualificationResult query(ServiceQualificationRequest request) {
        Map<String, Object> place = placeOf(request);
        String postCode = postCodeOf(place);
        List<QueryItemView> items = new ArrayList<>();
        int n = 1;
        for (CoverageMap row : bestRowsAt(postCode)) {
            items.add(QueryItemView.of(n++, serviceView(row)));
        }
        return QueryServiceQualificationResult.of(UUID.randomUUID().toString(),
                new SearchCriteria(place), items);
    }

    /* =====================================================================
     * checkServiceQualification: "can you deliver THIS here?" — verdict
     * per requested technology, alternative proposed on refusal, kept.
     * ===================================================================== */

    @Transactional
    public CheckServiceQualificationView check(ServiceQualificationRequest request) {
        List<Map<String, Object>> items = listOf(request.serviceQualificationItem());
        if (items.isEmpty()) {
            throw new BadRequestException("serviceQualificationItem is required");
        }
        Map<String, Object> topPlace = placeOf(request);
        List<CheckItemView> outItems = new ArrayList<>();
        boolean allQualified = true;
        int n = 1;
        for (Map<String, Object> item : items) {
            Map<String, Object> place = item.containsKey("place") || serviceOf(item).containsKey("place")
                    ? placeOf(item) : topPlace;
            CheckItemView outItem = checkOne(item, place)
                    .withId(item.get("id") == null ? String.valueOf(n) : item.get("id"));
            allQualified &= outItem.isQualified();
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
    public CheckServiceQualificationView findCheck(String id) {
        ServiceQualification row = qualifications
                .findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CheckServiceQualification", id));
        return checkView(row, readItems(row.getResultJson()));
    }

    @Transactional(readOnly = true)
    public List<CheckServiceQualificationView> listChecks() {
        return qualifications.findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(r -> checkView(r, readItems(r.getResultJson()))).toList();
    }

    /* ---------- the verdict ---------- */

    private CheckItemView checkOne(Map<String, Object> item, Map<String, Object> place) {
        String postCode = postCodeOf(place);
        String technology = requestedTechnology(item);
        if (technology == null) {
            return CheckItemView.refused(new Reason("missingService",
                    "qualification item without a requested service"
                            + " (serviceSpecification.name or a 'technology' characteristic)"));
        }
        Integer minDown = requestedMinDown(item);
        Optional<CoverageMap> best = bestRowAt(postCode, technology);
        boolean bandwidthOk = best.isPresent()
                && (minDown == null || best.get().getMaxDownMbps() >= minDown);
        if (best.isPresent() && bandwidthOk) {
            return CheckItemView.qualified(serviceView(best.get()));
        }
        String label = best.isEmpty()
                ? technology + " is not available at postcode "
                        + (postCode.isEmpty() ? "(none given)" : postCode)
                : technology + " at postcode " + postCode + " delivers at most "
                        + best.get().getMaxDownMbps() + " Mbps, below the requested " + minDown;
        CheckItemView outItem = CheckItemView.refused(new Reason(
                best.isEmpty() ? "noCoverage" : "insufficientBandwidth", label));
        // the TMF645 signature: never a bare no — the best this address CAN have
        Optional<CoverageMap> alternative = bestRowsAt(postCode).stream()
                .filter(r -> !r.getTechnology().equals(technology) || !bandwidthOk)
                .max(Comparator.comparing(CoverageMap::getMaxDownMbps));
        return alternative.isEmpty() ? outItem
                : outItem.withAlternative(AlternateServiceProposal.of(serviceView(alternative.get())));
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

    private ServiceView serviceView(CoverageMap row) {
        List<Characteristic> characteristics = new ArrayList<>();
        characteristics.add(new Characteristic("technology", row.getTechnology()));
        characteristics.add(new Characteristic("maxDownstreamMbps", row.getMaxDownMbps()));
        if (row.getMaxUpMbps() != null) {
            characteristics.add(new Characteristic("maxUpstreamMbps", row.getMaxUpMbps()));
        }
        // open access: name the fibre owner + the layer, when this footprint is
        // served by a wholesaler rather than our own network
        if (row.getAccessOwner() != null) {
            characteristics.add(new Characteristic("accessOwner", row.getAccessOwner()));
        }
        if (row.getAccessLayer() != null) {
            characteristics.add(new Characteristic("accessLayer", row.getAccessLayer()));
        }
        return ServiceView.of(row.getTechnology(), characteristics);
    }

    /* =====================================================================
     * queryAccessOptions: the WHOLESALE question — "which access owners can
     * serve this address, at what layer and bandwidth?" Open access turns one
     * footprint into a shortlist of suppliers a retail ISP can buy from. Only
     * owner-served rows (access_owner set) are options; our own network is not
     * a wholesale option. Best bandwidth per (owner, layer), longest prefix.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public AccessOptionsResult accessOptions(ServiceQualificationRequest request) {
        Map<String, Object> place = placeOf(request);
        String postCode = postCodeOf(place);
        String technology = Optional.ofNullable(requestedTechnologyIn(serviceOf(request)))
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
        List<AccessOption> options = new ArrayList<>();
        for (CoverageMap row : best.values()) {
            options.add(AccessOption.of(row.getAccessOwner(), row.getAccessLayer(),
                    row.getTechnology(), row.getMaxDownMbps(), row.getMaxUpMbps()));
        }
        options.sort(Comparator.comparingInt(AccessOption::maxDownMbps).reversed());
        return AccessOptionsResult.of(place, technology, options);
    }

    private static String technologyCriterion(ServiceQualificationRequest request) {
        if (request.searchCriteria() instanceof Map<?, ?> c && c.get("technology") != null) {
            return String.valueOf(c.get("technology"));
        }
        return request.technology() == null ? null : String.valueOf(request.technology());
    }

    private CheckServiceQualificationView checkView(ServiceQualification row,
            List<CheckItemView> items) {
        return CheckServiceQualificationView.of(row.getId(), row.getHref(), row.getState(),
                row.getQualificationResult(), readPlace(row.getPlaceJson()), items,
                row.getCreatedAt());
    }

    /* ---------- request parsing ---------- */

    /** The place, wherever the caller put it: searchCriteria.place, the
     * request's own service block, or top-level. */
    private static Map<String, Object> placeOf(ServiceQualificationRequest request) {
        Object candidate = null;
        if (request.searchCriteria() instanceof Map<?, ?> criteria) {
            candidate = criteria.get("place");
        }
        if (candidate == null) {
            candidate = serviceOf(request).get("place");
        }
        if (candidate == null) {
            candidate = request.place();
        }
        return firstPlace(candidate);
    }

    /** The same rule for one item inside the request. */
    private static Map<String, Object> placeOf(Map<String, Object> item) {
        Object candidate = null;
        if (item.get("searchCriteria") instanceof Map<?, ?> criteria) {
            candidate = criteria.get("place");
        }
        if (candidate == null) {
            candidate = serviceOf(item).get("place");
        }
        if (candidate == null) {
            candidate = item.get("place");
        }
        return firstPlace(candidate);
    }

    private static Map<String, Object> firstPlace(Object candidate) {
        if (candidate instanceof List<?> list && !list.isEmpty()) {
            candidate = list.get(0);
        }
        return candidate instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    private static String postCodeOf(Map<String, Object> place) {
        return place.get("postCode") == null ? ""
                : String.valueOf(place.get("postCode")).replaceAll("\\s", "");
    }

    private static Map<String, Object> serviceOf(ServiceQualificationRequest request) {
        return request.service() instanceof Map<?, ?> service ? castMap(service) : Map.of();
    }

    private static Map<String, Object> serviceOf(Map<String, Object> item) {
        return item.get("service") instanceof Map<?, ?> service ? castMap(service) : Map.of();
    }

    private static String requestedTechnology(Map<String, Object> item) {
        return requestedTechnologyIn(serviceOf(item));
    }

    private static String requestedTechnologyIn(Map<String, Object> service) {
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

    private List<CheckItemView> readItems(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, STORED_ITEMS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored qualification result is unreadable", e);
        }
    }

    private Map<String, Object> readPlace(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, STORED_PLACE);
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
