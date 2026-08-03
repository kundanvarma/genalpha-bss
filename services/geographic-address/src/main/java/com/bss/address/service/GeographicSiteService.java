package com.bss.address.service;

import com.bss.address.entity.GeographicAddress;
import com.bss.address.entity.GeographicSite;
import com.bss.address.exception.BadRequestException;
import com.bss.address.exception.NotFoundException;
import com.bss.address.repository.GeographicAddressRepository;
import com.bss.address.repository.GeographicSiteRepository;
import com.bss.address.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF674, the house way: the site LEANS on TMF673 next door — its place is
 * a stored geographic_address row, validated to exist at create, embedded
 * on every read. A site answers "where is the Oslo branch?" with a name, a
 * lifecycle status, an owner, and the address entered exactly once.
 */
@Service
public class GeographicSiteService {

    private static final String BASE = "/tmf-api/geographicSiteManagement/v4";
    private static final Set<String> STATUSES = Set.of(
            GeographicSite.PLANNED, GeographicSite.ACTIVE, GeographicSite.RETIRED);
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {
    };

    private final GeographicSiteRepository sites;
    private final GeographicAddressRepository addresses;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public GeographicSiteService(GeographicSiteRepository sites,
            GeographicAddressRepository addresses, TenantScope tenantScope,
            ObjectMapper objectMapper) {
        this.sites = sites;
        this.addresses = addresses;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String relatedPartyId) {
        return sites.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(s -> relatedPartyId == null || readParties(s.getRelatedPartyJson())
                        .stream().anyMatch(p -> relatedPartyId.equals(String.valueOf(p.get("id")))))
                .map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(require(id));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || String.valueOf(dto.get("name")).isBlank()) {
            throw new BadRequestException("name is required — a site IS a named place");
        }
        String addressId = placeRefOf(dto);
        if (addressId == null) {
            throw new BadRequestException(
                    "place.id is required and must reference a stored geographicAddress");
        }
        requireAddress(addressId);
        GeographicSite entity = new GeographicSite();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(BASE + "/geographicSite/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setDescription(dto.get("description") == null ? null
                : String.valueOf(dto.get("description")));
        entity.setStatus(dto.get("status") == null ? GeographicSite.PLANNED
                : requireStatus(dto.get("status")));
        entity.setRelatedPartyJson(writeJson(dto.get("relatedParty")));
        entity.setAddressId(addressId);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(sites.save(entity));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> dto) {
        GeographicSite entity = require(id);
        if (dto.get("status") != null) {
            entity.setStatus(requireStatus(dto.get("status")));
        }
        if (dto.get("name") != null) {
            entity.setName(String.valueOf(dto.get("name")));
        }
        if (dto.get("description") != null) {
            entity.setDescription(String.valueOf(dto.get("description")));
        }
        String addressId = placeRefOf(dto);
        if (addressId != null) {
            requireAddress(addressId);
            entity.setAddressId(addressId);
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(sites.save(entity));
    }

    @Transactional
    public void delete(String id) {
        sites.delete(require(id));
    }

    /* ---------- internals ---------- */

    private GeographicSite require(String id) {
        return sites.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("GeographicSite", id));
    }

    private GeographicAddress requireAddress(String addressId) {
        return addresses.findByIdAndTenantId(addressId, tenantScope.currentTenantId())
                .orElseThrow(() -> new BadRequestException(
                        "place.id '" + addressId + "' is not a stored geographicAddress"));
    }

    private static String requireStatus(Object status) {
        String value = String.valueOf(status);
        if (!STATUSES.contains(value)) {
            throw new BadRequestException("status must be one of " + STATUSES);
        }
        return value;
    }

    private static String placeRefOf(Map<String, Object> dto) {
        Object place = dto.get("place");
        if (place instanceof List<?> list && !list.isEmpty()) {
            place = list.get(0);
        }
        return place instanceof Map<?, ?> ref && ref.get("id") != null
                ? String.valueOf(ref.get("id")) : null;
    }

    private Map<String, Object> toMap(GeographicSite s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("href", s.getHref());
        map.put("name", s.getName());
        if (s.getDescription() != null) {
            map.put("description", s.getDescription());
        }
        map.put("status", s.getStatus());
        List<Map<String, Object>> parties = readParties(s.getRelatedPartyJson());
        if (!parties.isEmpty()) {
            map.put("relatedParty", parties);
        }
        // the place, EMBEDDED: the site answers "where?" without a second call
        addresses.findByIdAndTenantId(s.getAddressId(), s.getTenantId()).ifPresent(a -> {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("id", a.getId());
            place.put("street1", a.getStreet1());
            if (a.getStreet2() != null) {
                place.put("street2", a.getStreet2());
            }
            place.put("postCode", a.getPostCode());
            place.put("city", a.getCity());
            if (a.getStateOrProvince() != null) {
                place.put("stateOrProvince", a.getStateOrProvince());
            }
            place.put("country", a.getCountry());
            place.put("@referredType", "GeographicAddress");
            map.put("place", List.of(place));
        });
        map.put("lastUpdate", s.getLastUpdate());
        map.put("@type", "GeographicSite");
        return map;
    }

    private List<Map<String, Object>> readParties(String json) {
        try {
            return json == null || "null".equals(json) ? List.of()
                    : objectMapper.readValue(json, JSON_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored related parties are unreadable", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }
}
