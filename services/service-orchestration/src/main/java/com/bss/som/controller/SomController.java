package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.entity.ResourcePool;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-side TMF641/638: what the production layer did and what is running. */
@RestController
public class SomController {

    private final ServiceOrderRepository serviceOrders;
    private final ServiceInstanceRepository services;
    private final ResourcePoolRepository pools;
    private final ResourceAssignmentRepository assignments;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;

    public SomController(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            TenantScope tenantScope, PartyScope partyScope) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.pools = pools;
        this.assignments = assignments;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/serviceOrder")
    public ResponseEntity<List<Map<String, Object>>> serviceOrders(
            @RequestParam(required = false) String productOrderId) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceOrder> rows = productOrderId != null
                ? serviceOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)
                : serviceOrders.findAll().stream()
                        .filter(o -> tenant.equals(o.getTenantId())).toList();
        return ResponseEntity.ok(rows.stream().map(this::orderMap).toList());
    }

    @GetMapping(ApiConstants.INVENTORY_BASE + "/service")
    public ResponseEntity<List<Map<String, Object>>> services(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId) {
        String tenant = tenantScope.currentTenantId();
        // Customers see their own running services; staff filter freely.
        String party = partyScope.scopedPartyId().orElse(relatedPartyId);
        List<ServiceInstance> rows = party != null
                ? services.findByTenantIdAndOwnerPartyId(tenant, party)
                : services.findAll().stream()
                        .filter(s -> tenant.equals(s.getTenantId())).toList();
        return ResponseEntity.ok(rows.stream().map(this::serviceMap).toList());
    }

    private Map<String, Object> orderMap(ServiceOrder o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("href", o.getHref());
        map.put("state", o.getState());
        map.put("category", o.getItemName());
        map.put("productOrderId", o.getProductOrderId());
        if (o.getCompletedAt() != null) map.put("completionDate", o.getCompletedAt().toString());
        map.put("@type", "ServiceOrder");
        return map;
    }

    @PostMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<Map<String, Object>> createPool(@RequestBody Map<String, Object> dto) {
        ResourcePool pool = new ResourcePool();
        pool.setId(java.util.UUID.randomUUID().toString());
        pool.setTenantId(tenantScope.currentTenantId());
        pool.setHref("/tmf-api/resourcePoolManagement/v4/resourcePool/" + pool.getId());
        pool.setName(String.valueOf(dto.getOrDefault("name", "numbers")));
        pool.setResourceType(String.valueOf(dto.getOrDefault("resourceType", ResourcePool.MSISDN)));
        pool.setPrefix(String.valueOf(dto.get("prefix")));
        pool.setNextValue(dto.get("nextValue") instanceof Number n ? n.longValue() : 1L);
        pool.setCreatedAt(java.time.OffsetDateTime.now());
        pool.setLastUpdate(java.time.OffsetDateTime.now());
        pools.save(pool);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pool.getId());
        map.put("name", pool.getName());
        map.put("resourceType", pool.getResourceType());
        map.put("prefix", pool.getPrefix());
        map.put("@type", "ResourcePool");
        return ResponseEntity.status(HttpStatus.CREATED).body(map);
    }

    @GetMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<List<Map<String, Object>>> listPools() {
        return ResponseEntity.ok(pools.findByTenantId(tenantScope.currentTenantId()).stream()
                .map(p -> {
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    map.put("id", p.getId());
                    map.put("name", p.getName());
                    map.put("resourceType", p.getResourceType());
                    map.put("prefix", p.getPrefix());
                    map.put("@type", "ResourcePool");
                    return map;
                }).toList());
    }

    private Map<String, Object> serviceMap(ServiceInstance s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("href", s.getHref());
        map.put("name", s.getName());
        map.put("state", s.getState());
        map.put("serviceOrderId", s.getServiceOrderId());
        if (s.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", s.getOwnerPartyId(), "role", "customer")));
        }
        List<Map<String, Object>> supporting = assignments
                .findByTenantIdAndServiceId(s.getTenantId(), s.getId()).stream()
                .map(a -> Map.<String, Object>of("value", a.getValue(), "@referredType", "Resource"))
                .toList();
        if (!supporting.isEmpty()) {
            map.put("supportingResource", supporting);
        }
        map.put("@type", "Service");
        return map;
    }
}
