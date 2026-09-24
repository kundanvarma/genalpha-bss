package com.bss.address.service;

import com.bss.address.dto.GeographicSiteRequest;
import com.bss.address.dto.GeographicSiteView;
import com.bss.address.dto.SitePlace;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.bss.address.api.Wire.idOf;

/**
 * TMF674, the house way: the site LEANS on TMF673 next door — its place is
 * a stored geographic_address row, validated to exist at create, embedded
 * on every read. A site answers "where is the Oslo branch?" with a name, a
 * lifecycle status, an owner, and the address entered exactly once.
 */
@Service
public class GeographicSiteService {

    private static final String BASE = "/tmf-api/geographicSiteManagement/v4";
    /** The order this table is PRINTED in, kept as the wire has it: a
     * {@code Set.of} re-shuffles the refusal sentence on every JVM start. */
    private static final Set<String> STATUSES = new LinkedHashSet<>(List.of(
            GeographicSite.ACTIVE, GeographicSite.RETIRED, GeographicSite.PLANNED));
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
    public List<GeographicSiteView> findAll(String relatedPartyId) {
        return sites.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(s -> relatedPartyId == null || readParties(s.getRelatedPartyJson())
                        .stream().anyMatch(p -> relatedPartyId.equals(idOf(p))))
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public GeographicSiteView findById(String id) {
        return toView(require(id));
    }

    @Transactional
    public GeographicSiteView create(GeographicSiteRequest dto) {
        if (dto.name() == null || dto.name().isBlank()) {
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
        entity.setName(dto.name());
        entity.setDescription(dto.description());
        entity.setStatus(dto.status() == null ? GeographicSite.PLANNED
                : requireStatus(dto.status()));
        entity.setRelatedPartyJson(writeJson(dto.relatedParty()));
        entity.setAddressId(addressId);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(sites.save(entity));
    }

    @Transactional
    public GeographicSiteView patch(String id, GeographicSiteRequest dto) {
        GeographicSite entity = require(id);
        if (dto.status() != null) {
            entity.setStatus(requireStatus(dto.status()));
        }
        if (dto.name() != null) {
            entity.setName(dto.name());
        }
        if (dto.description() != null) {
            entity.setDescription(dto.description());
        }
        String addressId = placeRefOf(dto);
        if (addressId != null) {
            requireAddress(addressId);
            entity.setAddressId(addressId);
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(sites.save(entity));
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

    private static String requireStatus(String value) {
        if (!STATUSES.contains(value)) {
            throw new BadRequestException("status must be one of " + STATUSES);
        }
        return value;
    }

    private static String placeRefOf(GeographicSiteRequest dto) {
        Object place = dto.place();
        if (place instanceof List<?> list && !list.isEmpty()) {
            place = list.get(0);
        }
        return idOf(place);
    }

    private GeographicSiteView toView(GeographicSite s) {
        // the place, EMBEDDED: the site answers "where?" without a second call
        SitePlace place = addresses.findByIdAndTenantId(s.getAddressId(), s.getTenantId())
                .map(SitePlace::of).orElse(null);
        return GeographicSiteView.of(s.getId(), s.getHref(), s.getName(), s.getDescription(),
                s.getStatus(), readParties(s.getRelatedPartyJson()), place, s.getLastUpdate());
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
