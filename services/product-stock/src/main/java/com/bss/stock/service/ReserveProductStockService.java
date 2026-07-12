package com.bss.stock.service;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.entity.ReserveProductStock;
import com.bss.stock.exception.NotFoundException;
import com.bss.stock.repository.ReserveProductStockRepository;
import com.bss.stock.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** TMF687 ReserveProductStock resource: create / list / retrieve, echoing the posted body. */
@Service
public class ReserveProductStockService {

    private static final String RESOURCE = "ReserveProductStock";

    private final ReserveProductStockRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReserveProductStockService(ReserveProductStockRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        ReserveProductStock entity = new ReserveProductStock();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/reserveProductStock/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setState(body.get("state") == null ? "reserved" : String.valueOf(body.get("state")));
        entity.setPayloadJson(writeJson(body));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(Map<String, String> filters) {
        return repository.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.getId()))
                .filter(r -> filters.get("href") == null || filters.get("href").equals(r.getHref()))
                .filter(r -> filters.get("state") == null || filters.get("state").equals(r.getState()))
                .map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(ReserveProductStock e) {
        Map<String, Object> map = new LinkedHashMap<>();
        Object stored = readJson(e.getPayloadJson());
        if (stored instanceof Map<?, ?> m) {
            map.putAll((Map<String, Object>) m);
        }
        map.put("id", e.getId());
        map.put("href", e.getHref());
        // TMF687 mandatory: reserveProductStockItem (from payload) + reserveProductStockState.
        map.put("reserveProductStockState", e.getState());
        map.putIfAbsent("reserveProductStockItem", List.of());
        map.put("@type", "ReserveProductStock");
        return map;
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJson(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(s, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
