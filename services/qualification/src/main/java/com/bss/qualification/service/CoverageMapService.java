package com.bss.qualification.service;

import com.bss.qualification.entity.CoverageMap;
import com.bss.qualification.events.DomainEventPublisher;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.CoverageMapRepository;
import com.bss.qualification.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-managed footprint data: which technology reaches which prefix. */
@Service
public class CoverageMapService {

    private final CoverageMapRepository repository;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public CoverageMapService(CoverageMapRepository repository, DomainEventPublisher events,
            TenantScope tenantScope) {
        this.repository = repository;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        return repository.findByTenantId(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("technology") == null || String.valueOf(dto.get("technology")).isBlank()) {
            throw new BadRequestException("technology is required");
        }
        if (dto.get("maxDownMbps") == null) {
            throw new BadRequestException("maxDownMbps is required");
        }
        CoverageMap row = new CoverageMap();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setTechnology(String.valueOf(dto.get("technology")).toLowerCase());
        // empty prefix = the everywhere-fallback, stored as ''
        row.setPostcodePrefix(dto.get("postcodePrefix") == null ? ""
                : String.valueOf(dto.get("postcodePrefix")).replaceAll("\\s", ""));
        row.setMaxDownMbps(intOf(dto.get("maxDownMbps"), "maxDownMbps"));
        row.setMaxUpMbps(dto.get("maxUpMbps") == null ? null : intOf(dto.get("maxUpMbps"), "maxUpMbps"));
        row.setNote(dto.get("note") == null ? null : String.valueOf(dto.get("note")));
        // open access: a third-party fibre owner + the layer they sell
        row.setAccessOwner(dto.get("accessOwner") == null ? null : String.valueOf(dto.get("accessOwner")));
        row.setAccessLayer(dto.get("accessLayer") == null ? null : String.valueOf(dto.get("accessLayer")));
        row.setHref("/tmf-api/serviceQualificationManagement/v4/coverageMap/" + row.getId());
        row.setLastUpdate(OffsetDateTime.now());
        repository.save(row);
        events.publish("CoverageMapCreateEvent", "coverageMap", toMap(row));
        return toMap(row);
    }

    @Transactional
    public void delete(String id) {
        CoverageMap row = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CoverageMap", id));
        repository.delete(row);
        events.publish("CoverageMapDeleteEvent", "coverageMap", toMap(row));
    }

    private static Integer intOf(Object value, String field) {
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number");
        }
    }

    private Map<String, Object> toMap(CoverageMap row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.getId());
        map.put("href", row.getHref());
        map.put("technology", row.getTechnology());
        map.put("postcodePrefix", row.getPostcodePrefix());
        map.put("maxDownMbps", row.getMaxDownMbps());
        if (row.getMaxUpMbps() != null) {
            map.put("maxUpMbps", row.getMaxUpMbps());
        }
        if (row.getNote() != null) {
            map.put("note", row.getNote());
        }
        map.put("lastUpdate", row.getLastUpdate());
        map.put("@type", "CoverageMap");
        return map;
    }
}
