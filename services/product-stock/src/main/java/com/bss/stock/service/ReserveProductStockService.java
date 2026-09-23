package com.bss.stock.service;

import com.bss.stock.api.ApiConstants;
import com.bss.stock.dto.ReserveProductStockView;
import com.bss.stock.entity.ReserveProductStock;
import com.bss.stock.exception.NotFoundException;
import com.bss.stock.repository.ReserveProductStockRepository;
import com.bss.stock.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    public ReserveProductStockView create(ObjectNode body) {
        ReserveProductStock entity = new ReserveProductStock();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/reserveProductStock/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setState(body.hasNonNull("state") ? body.get("state").asText() : "reserved");
        entity.setPayloadJson(body.toString());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<ReserveProductStockView> findAll(Map<String, String> filters) {
        return repository.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.getId()))
                .filter(r -> filters.get("href") == null || filters.get("href").equals(r.getHref()))
                .filter(r -> filters.get("state") == null || filters.get("state").equals(r.getState()))
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ReserveProductStockView findById(String id) {
        return toView(repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id)));
    }

    private ReserveProductStockView toView(ReserveProductStock e) {
        ObjectNode stored = storedBody(e.getPayloadJson());
        // TMF687 mandatory: reserveProductStockItem (from the payload) + reserveProductStockState.
        JsonNode items = stored.has("reserveProductStockItem")
                ? stored.get("reserveProductStockItem") : mapper.createArrayNode();
        Map<String, JsonNode> rest = new LinkedHashMap<>();
        stored.fields().forEachRemaining(f -> {
            if (!ReserveProductStockView.DECLARED.contains(f.getKey())) {
                rest.put(f.getKey(), f.getValue());
            }
        });
        return new ReserveProductStockView(e.getId(), e.getHref(), e.getState(), items,
                "ReserveProductStock", rest);
    }

    /** The document the poster wrote, exactly as it was stored. */
    private ObjectNode storedBody(String stored) {
        if (stored == null || stored.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(stored);
            return node instanceof ObjectNode o ? o : mapper.createObjectNode();
        } catch (Exception ex) {
            return mapper.createObjectNode();
        }
    }
}
