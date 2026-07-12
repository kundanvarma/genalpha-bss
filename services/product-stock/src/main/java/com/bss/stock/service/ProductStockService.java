package com.bss.stock.service;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.api.OffsetPageRequest;
import com.bss.stock.api.PagedResult;
import com.bss.stock.dto.ProductStockDto;
import com.bss.stock.entity.ProductStock;
import com.bss.stock.events.DomainEventPublisher;
import com.bss.stock.exception.BadRequestException;
import com.bss.stock.exception.NotFoundException;
import com.bss.stock.mapper.ProductStockMapper;
import com.bss.stock.repository.ProductStockRepository;
import com.bss.stock.repository.StockReservationRepository;
import com.bss.stock.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductStockService {

    private static final String RESOURCE = "ProductStock";

    private final ProductStockRepository repository;
    private final StockReservationRepository reservations;
    private final ProductStockMapper mapper;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ProductStockService(ProductStockRepository repository, StockReservationRepository reservations,
            ProductStockMapper mapper, DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.reservations = reservations;
        this.mapper = mapper;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Echo the posted body (so spec fields round-trip) overlaid with the
     * server-managed id/href and the live stocked/reserved/available quantities.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(ProductStock entity) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        Object stored = readJson(entity.getPayloadJson());
        if (stored instanceof Map<?, ?> m) {
            map.putAll((Map<String, Object>) m);
        }
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        if (entity.getName() != null) {
            map.put("name", entity.getName());
        }
        int active = reservations.activeQuantityFor(entity.getId(), tenantScope.currentTenantId());
        int stocked = entity.getStockedAmount() == null ? 0 : entity.getStockedAmount();
        String units = entity.getStockedUnits() == null ? "unit" : entity.getStockedUnits();
        map.put("stockedQuantity", Map.of("amount", stocked, "units", units));
        map.put("reservedQuantity", Map.of("amount", active, "units", units));
        map.put("availableQuantity", Map.of("amount", stocked - active, "units", units));
        // TMF687 mandatory attributes — always present, even for app-created rows.
        map.putIfAbsent("productStockLevel", Map.of("amount", stocked));
        map.putIfAbsent("productStockStatusType", "available");
        map.putIfAbsent("stockedProduct", entity.getProductOfferingId() == null
                ? Map.of() : Map.of("id", entity.getProductOfferingId()));
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "ProductStock");
        return map;
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ProductStock> page = repository.findAll(Example.of(probeFor(filters)),
                new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    private ProductStock probeFor(Map<String, String> filters) {
        ProductStock probe = new ProductStock();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "href" -> probe.setHref(f.getValue());
                case "name" -> probe.setName(f.getValue());
                case "productOfferingId" -> probe.setProductOfferingId(f.getValue());
                // TMF630 field-selection / sorting are not filters.
                case "fields", "sort" -> { }
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        return probe;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        ProductStock entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return toMap(entity);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        ProductStock entity = new ProductStock();
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productStock/" + id);
        entity.setName(dto.get("name") == null ? null : String.valueOf(dto.get("name")));
        entity.setStockedAmount(amountIn(dto));
        entity.setStockedUnits(unitsIn(dto));
        if (dto.get("productOffering") instanceof Map<?, ?> po && po.get("id") != null) {
            entity.setProductOfferingId(String.valueOf(po.get("id")));
        }
        entity.setPayloadJson(writeJson(dto));
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("ProductStockCreateEvent", "productStock", created);
        return created;
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        ProductStock entity = repository.findForUpdateById(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (patch.get("name") != null) {
            entity.setName(String.valueOf(patch.get("name")));
        }
        if (patch.containsKey("productStockLevel") || patch.containsKey("stockedQuantity")) {
            entity.setStockedAmount(amountIn(patch));
        }
        // merge posted fields into the stored body so they round-trip
        Object stored = readJson(entity.getPayloadJson());
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (stored instanceof Map<?, ?> m) {
            merged.putAll(castMap(m));
        }
        merged.putAll(patch);
        entity.setPayloadJson(writeJson(merged));
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> updated = toMap(repository.save(entity));
        events.publish("ProductStockAttributeValueChangeEvent", "productStock", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductStock entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        Map<String, Object> deleted = toMap(entity);
        repository.delete(entity);
        events.publish("ProductStockDeleteEvent", "productStock", deleted);
    }

    /** Amount from productStockLevel.amount (spec) or stockedQuantity.amount (app), default 0. */
    private static int amountIn(Map<String, Object> dto) {
        for (String key : new String[] {"productStockLevel", "stockedQuantity"}) {
            if (dto.get(key) instanceof Map<?, ?> q && q.get("amount") instanceof Number n) {
                return n.intValue();
            }
        }
        return 0;
    }

    private static String unitsIn(Map<String, Object> dto) {
        for (String key : new String[] {"stockedQuantity", "productStockLevel"}) {
            if (dto.get(key) instanceof Map<?, ?> q && q.get("units") != null) {
                return String.valueOf(q.get("units"));
            }
        }
        return "unit";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readJson(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(s, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
