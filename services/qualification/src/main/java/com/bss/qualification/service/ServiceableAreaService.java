package com.bss.qualification.service;

import com.bss.qualification.api.ApiConstants;
import com.bss.qualification.api.OffsetPageRequest;
import com.bss.qualification.api.PagedResult;
import com.bss.qualification.dto.ServiceableAreaRequest;
import com.bss.qualification.dto.ServiceableAreaView;
import com.bss.qualification.entity.ServiceableArea;
import com.bss.qualification.events.DomainEventPublisher;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.ServiceableAreaRepository;
import com.bss.qualification.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Admin-managed rule data: where each gated offering can be delivered. */
@Service
public class ServiceableAreaService {

    private static final String RESOURCE = "ServiceableArea";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ServiceableAreaRepository repository;
    private final DomainEventPublisher events;
    private final ObjectMapper objectMapper;
    private final TenantScope tenantScope;

    public ServiceableAreaService(ServiceableAreaRepository repository, DomainEventPublisher events,
            ObjectMapper objectMapper, TenantScope tenantScope) {
        this.repository = repository;
        this.events = events;
        this.objectMapper = objectMapper;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public PagedResult<ServiceableAreaView> findAll(int offset, int limit, Map<String, String> filters) {
        ServiceableArea probe = new ServiceableArea();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "productOfferingId" -> probe.setProductOfferingId(f.getValue());
                case "postcodePrefix" -> probe.setPostcodePrefix(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        Page<ServiceableArea> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(),
                page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ServiceableAreaView findById(String id) {
        return toView(repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id)));
    }

    @Transactional
    public ServiceableAreaView create(ServiceableAreaRequest dto) {
        String prefix = dto.postcodePrefix();
        JsonNode offering = dto.productOffering();
        if (prefix == null || prefix.isBlank()) {
            throw new BadRequestException("postcodePrefix is required");
        }
        if (offering == null || !offering.isObject() || offering.get("id") == null
                || offering.get("id").isNull()) {
            throw new BadRequestException("productOffering.id is required");
        }
        ServiceableArea entity = new ServiceableArea();
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/serviceableArea/" + id);
        entity.setProductOfferingJson(writeJson(offering));
        entity.setProductOfferingId(offering.get("id").asText());
        entity.setPostcodePrefix(prefix);
        entity.setName(dto.name());
        entity.setLastUpdate(OffsetDateTime.now());
        ServiceableAreaView created = toView(repository.save(entity));
        events.publish("ServiceableAreaCreateEvent", "serviceableArea", created);
        return created;
    }

    @Transactional
    public void delete(String id) {
        ServiceableArea entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ServiceableAreaView deleted = toView(entity);
        repository.delete(entity);
        events.publish("ServiceableAreaDeleteEvent", "serviceableArea", deleted);
    }

    private ServiceableAreaView toView(ServiceableArea entity) {
        return ServiceableAreaView.of(entity.getId(), entity.getHref(), entity.getName(),
                readJson(entity.getProductOfferingJson()), entity.getPostcodePrefix(),
                entity.getLastUpdate());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON object", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored JSON object is unreadable", e);
        }
    }
}
