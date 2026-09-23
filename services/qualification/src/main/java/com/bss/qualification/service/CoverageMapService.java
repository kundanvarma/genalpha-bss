package com.bss.qualification.service;

import com.bss.qualification.dto.CoverageMapRequest;
import com.bss.qualification.dto.CoverageMapView;
import com.bss.qualification.entity.CoverageMap;
import com.bss.qualification.events.DomainEventPublisher;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.CoverageMapRepository;
import com.bss.qualification.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
    public List<CoverageMapView> findAll() {
        return repository.findByTenantId(tenantScope.currentTenantId())
                .stream().map(CoverageMapView::of).toList();
    }

    @Transactional
    public CoverageMapView create(CoverageMapRequest dto) {
        if (dto.technology() == null || dto.technology().isBlank()) {
            throw new BadRequestException("technology is required");
        }
        if (dto.maxDownMbps() == null) {
            throw new BadRequestException("maxDownMbps is required");
        }
        CoverageMap row = new CoverageMap();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setTechnology(dto.technology().toLowerCase());
        // empty prefix = the everywhere-fallback, stored as ''
        row.setPostcodePrefix(dto.postcodePrefix() == null ? ""
                : dto.postcodePrefix().replaceAll("\\s", ""));
        row.setMaxDownMbps(intOf(dto.maxDownMbps(), "maxDownMbps"));
        row.setMaxUpMbps(dto.maxUpMbps() == null ? null : intOf(dto.maxUpMbps(), "maxUpMbps"));
        row.setNote(dto.note());
        // open access: a third-party fibre owner + the layer they sell
        row.setAccessOwner(dto.accessOwner());
        row.setAccessLayer(dto.accessLayer());
        row.setHref("/tmf-api/serviceQualificationManagement/v4/coverageMap/" + row.getId());
        row.setLastUpdate(OffsetDateTime.now());
        repository.save(row);
        CoverageMapView view = CoverageMapView.of(row);
        events.publish("CoverageMapCreateEvent", "coverageMap", view);
        return view;
    }

    @Transactional
    public void delete(String id) {
        CoverageMap row = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("CoverageMap", id));
        repository.delete(row);
        events.publish("CoverageMapDeleteEvent", "coverageMap", CoverageMapView.of(row));
    }

    private static Integer intOf(String value, String field) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number");
        }
    }

}
