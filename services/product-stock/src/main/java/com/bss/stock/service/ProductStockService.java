package com.bss.stock.service;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.api.OffsetPageRequest;
import com.bss.stock.api.PagedResult;
import com.bss.stock.dto.ProductStockView;
import com.bss.stock.dto.Quantity;
import com.bss.stock.entity.ProductStock;
import com.bss.stock.events.DomainEventPublisher;
import com.bss.stock.exception.BadRequestException;
import com.bss.stock.exception.NotFoundException;
import com.bss.stock.repository.ProductStockRepository;
import com.bss.stock.repository.StockReservationRepository;
import com.bss.stock.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductStockService {

    private static final String RESOURCE = "ProductStock";

    private final ProductStockRepository repository;
    private final StockReservationRepository reservations;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public ProductStockService(ProductStockRepository repository, StockReservationRepository reservations,
            DomainEventPublisher events, TenantScope tenantScope) {
        this.repository = repository;
        this.reservations = reservations;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Echo the posted body (so spec fields round-trip) overlaid with the
     * server-managed id/href and the live stocked/reserved/available quantities.
     */
    private ProductStockView toView(ProductStock entity) {
        ObjectNode stored = storedBody(entity.getPayloadJson());
        int active = reservations.activeQuantityFor(entity.getId(), tenantScope.currentTenantId());
        int stocked = entity.getStockedAmount() == null ? 0 : entity.getStockedAmount();
        String units = entity.getStockedUnits() == null ? "unit" : entity.getStockedUnits();
        // TMF687 mandatory attributes — always present, even for app-created rows,
        // but a posted value wins over the computed default, exactly as before.
        JsonNode level = stored.has("productStockLevel") ? stored.get("productStockLevel")
                : json.createObjectNode().put("amount", stocked);
        JsonNode status = stored.has("productStockStatusType") ? stored.get("productStockStatusType")
                : json.getNodeFactory().textNode("available");
        JsonNode product = stored.has("stockedProduct") ? stored.get("stockedProduct")
                : entity.getProductOfferingId() == null ? json.createObjectNode()
                        : json.createObjectNode().put("id", entity.getProductOfferingId());
        String name = entity.getName() != null ? entity.getName()
                : stored.hasNonNull("name") ? stored.get("name").asText() : null;
        Map<String, JsonNode> rest = new LinkedHashMap<>();
        stored.fields().forEachRemaining(f -> {
            if (!ProductStockView.DECLARED.contains(f.getKey())) {
                rest.put(f.getKey(), f.getValue());
            }
        });
        return new ProductStockView(entity.getId(), entity.getHref(), name,
                new Quantity(stocked, units), new Quantity(active, units),
                new Quantity(stocked - active, units),
                level, status, product, entity.getLastUpdate(), "ProductStock", rest);
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductStockView> findAll(int offset, int limit, Map<String, String> filters) {
        Page<ProductStock> page = repository.findAll(Example.of(probeFor(filters)),
                new OffsetPageRequest(offset, limit));
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(), page.getTotalElements());
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
    public ProductStockView findById(String id) {
        ProductStock entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        return toView(entity);
    }

    @Transactional
    public ProductStockView create(ObjectNode dto) {
        ProductStock entity = new ProductStock();
        entity.setTenantId(tenantScope.currentTenantId());
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/productStock/" + id);
        entity.setName(dto.hasNonNull("name") ? dto.get("name").asText() : null);
        entity.setStockedAmount(amountIn(dto));
        entity.setStockedUnits(unitsIn(dto));
        if (dto.path("productOffering").hasNonNull("id")) {
            entity.setProductOfferingId(dto.path("productOffering").get("id").asText());
        }
        entity.setPayloadJson(dto.toString());
        entity.setLastUpdate(OffsetDateTime.now());
        ProductStockView created = toView(repository.save(entity));
        events.publish("ProductStockCreateEvent", "productStock", created);
        return created;
    }

    @Transactional
    public ProductStockView patch(String id, ObjectNode patch) {
        ProductStock entity = repository.findForUpdateById(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        if (patch.hasNonNull("name")) {
            entity.setName(patch.get("name").asText());
        }
        if (patch.has("productStockLevel") || patch.has("stockedQuantity")) {
            entity.setStockedAmount(amountIn(patch));
        }
        // merge posted fields into the stored body so they round-trip
        ObjectNode merged = storedBody(entity.getPayloadJson());
        merged.setAll(patch);
        entity.setPayloadJson(merged.toString());
        entity.setLastUpdate(OffsetDateTime.now());
        ProductStockView updated = toView(repository.save(entity));
        events.publish("ProductStockAttributeValueChangeEvent", "productStock", updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        ProductStock entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        ProductStockView deleted = toView(entity);
        repository.delete(entity);
        events.publish("ProductStockDeleteEvent", "productStock", deleted);
    }

    /** Amount from productStockLevel.amount (spec) or stockedQuantity.amount (app), default 0. */
    private static int amountIn(JsonNode dto) {
        for (String key : new String[] {"productStockLevel", "stockedQuantity"}) {
            if (dto.path(key).path("amount").isNumber()) {
                return dto.path(key).path("amount").intValue();
            }
        }
        return 0;
    }

    private static String unitsIn(JsonNode dto) {
        for (String key : new String[] {"stockedQuantity", "productStockLevel"}) {
            if (dto.path(key).hasNonNull("units")) {
                return dto.path(key).get("units").asText();
            }
        }
        return "unit";
    }

    /** The document the poster wrote, exactly as it was stored. */
    private ObjectNode storedBody(String stored) {
        if (stored == null || stored.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(stored);
            return node instanceof ObjectNode o ? o : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
