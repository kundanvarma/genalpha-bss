package com.bss.address.service;

import com.bss.address.api.ApiConstants;
import com.bss.address.api.OffsetPageRequest;
import com.bss.address.api.PagedResult;
import com.bss.address.entity.GeographicAddress;
import com.bss.address.exception.BadRequestException;
import com.bss.address.exception.NotFoundException;
import com.bss.address.repository.GeographicAddressRepository;
import com.bss.address.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TMF673: one place that knows what a deliverable address looks like.
 * Validation is the anonymous shop-window operation — normalize, apply the
 * per-country postcode rule, and either bless a standardized form or say
 * exactly what is wrong. Validated addresses can be stored so orders and
 * appointments reference an id instead of re-typing structures.
 */
@Service
public class GeographicAddressService {

    /** Dev footprint: Nordic market, digits-only postcodes. */
    private static final Map<String, Pattern> POSTCODE_RULES = Map.of(
            "SE", Pattern.compile("\\d{5}"),
            "NO", Pattern.compile("\\d{4}"),
            "DK", Pattern.compile("\\d{4}"),
            "FI", Pattern.compile("\\d{5}"));
    private static final Set<String> REQUIRED = Set.of("street1", "postCode", "city", "country");

    private final GeographicAddressRepository repository;
    private final TenantScope tenantScope;

    public GeographicAddressService(GeographicAddressRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    /** Anonymous: normalize + judge. Returns the TMF673 validation shape. */
    public Map<String, Object> validate(Map<String, Object> request) {
        if (!(request.get("submittedGeographicAddress") instanceof Map<?, ?> submittedRaw)) {
            throw new BadRequestException("submittedGeographicAddress is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> submitted = (Map<String, Object>) submittedRaw;

        Map<String, Object> standardized = new LinkedHashMap<>();
        StringBuilder problem = new StringBuilder();
        for (String field : new String[] {"street1", "street2", "postCode", "city", "stateOrProvince", "country"}) {
            String value = submitted.get(field) == null ? null : String.valueOf(submitted.get(field)).trim();
            if ((value == null || value.isEmpty()) && REQUIRED.contains(field)) {
                problem.append(problem.isEmpty() ? "" : "; ").append(field).append(" is required");
                continue;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            standardized.put(field, switch (field) {
                case "country" -> value.toUpperCase(Locale.ROOT);
                case "postCode" -> value.replace(" ", "");
                case "city" -> titleCase(value);
                default -> value;
            });
        }
        if (problem.isEmpty()) {
            String country = String.valueOf(standardized.get("country"));
            Pattern rule = POSTCODE_RULES.get(country);
            if (rule == null) {
                problem.append("country '").append(country).append("' is not served");
            } else if (!rule.matcher(String.valueOf(standardized.get("postCode"))).matches()) {
                problem.append("postCode '").append(standardized.get("postCode"))
                        .append("' is not a valid ").append(country).append(" postcode");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", UUID.randomUUID().toString());
        result.put("@type", "GeographicAddressValidation");
        result.put("submittedGeographicAddress", submitted);
        if (problem.isEmpty()) {
            result.put("validationResult", "success");
            standardized.put("@type", "GeographicAddress");
            result.put("standardizedGeographicAddress", standardized);
        } else {
            result.put("validationResult", "failed");
            result.put("validationReason", problem.toString());
        }
        return result;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        Map<String, Object> validation = validate(Map.of("submittedGeographicAddress", dto));
        if (!"success".equals(validation.get("validationResult"))) {
            throw new BadRequestException(String.valueOf(validation.get("validationReason")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> std = (Map<String, Object>) validation.get("standardizedGeographicAddress");
        GeographicAddress entity = new GeographicAddress();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/geographicAddress/" + id);
        entity.setStreet1((String) std.get("street1"));
        entity.setStreet2((String) std.get("street2"));
        entity.setPostCode((String) std.get("postCode"));
        entity.setCity((String) std.get("city"));
        entity.setStateOrProvince((String) std.get("stateOrProvince"));
        entity.setCountry((String) std.get("country"));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("GeographicAddress", id)));
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        GeographicAddress probe = new GeographicAddress();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "postCode" -> probe.setPostCode(f.getValue());
                case "city" -> probe.setCity(f.getValue());
                case "country" -> probe.setCountry(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        Page<GeographicAddress> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    private String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean start = true;
        for (char c : value.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        return out.toString();
    }

    private Map<String, Object> toMap(GeographicAddress a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("href", a.getHref());
        if (a.getStreet1() != null) map.put("street1", a.getStreet1());
        if (a.getStreet2() != null) map.put("street2", a.getStreet2());
        if (a.getPostCode() != null) map.put("postCode", a.getPostCode());
        if (a.getCity() != null) map.put("city", a.getCity());
        if (a.getStateOrProvince() != null) map.put("stateOrProvince", a.getStateOrProvince());
        if (a.getCountry() != null) map.put("country", a.getCountry());
        map.put("@type", "GeographicAddress");
        return map;
    }
}
