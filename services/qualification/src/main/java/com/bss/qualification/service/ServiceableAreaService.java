package com.bss.qualification.service;

import com.bss.qualification.api.ApiConstants;
import com.bss.qualification.api.OffsetPageRequest;
import com.bss.qualification.api.PagedResult;
import com.bss.qualification.entity.ServiceableArea;
import com.bss.qualification.events.DomainEventPublisher;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.ServiceableAreaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

    public ServiceableAreaService(ServiceableAreaRepository repository, DomainEventPublisher events,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        ServiceableArea probe = new ServiceableArea();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "productOfferingId" -> probe.setProductOfferingId(f.getValue());
                case "postcodePrefix" -> probe.setPostcodePrefix(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        Page<ServiceableArea> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id)));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        Object prefix = dto.get("postcodePrefix");
        Object offering = dto.get("productOffering");
        if (prefix == null || String.valueOf(prefix).isBlank()) {
            throw new BadRequestException("postcodePrefix is required");
        }
        if (!(offering instanceof Map<?, ?> ref) || ref.get("id") == null) {
            throw new BadRequestException("productOffering.id is required");
        }
        ServiceableArea entity = new ServiceableArea();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/serviceableArea/" + id);
        entity.setProductOfferingJson(writeJson(dto.get("productOffering")));
        entity.setProductOfferingId(String.valueOf(ref.get("id")));
        entity.setPostcodePrefix(String.valueOf(prefix));
        entity.setName(dto.get("name") == null ? null : String.valueOf(dto.get("name")));
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("ServiceableAreaCreateEvent", "serviceableArea", created);
        return created;
    }

    @Transactional
    public void delete(String id) {
        ServiceableArea entity = repository.findById(id)
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        Map<String, Object> deleted = toMap(entity);
        repository.deleteById(id);
        events.publish("ServiceableAreaDeleteEvent", "serviceableArea", deleted);
    }

    private Map<String, Object> toMap(ServiceableArea entity) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        if (entity.getName() != null) {
            map.put("name", entity.getName());
        }
        map.put("productOffering", readJson(entity.getProductOfferingJson()));
        map.put("postcodePrefix", entity.getPostcodePrefix());
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "ServiceableArea");
        return map;
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
