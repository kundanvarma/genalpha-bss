package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final TenantScope tenantScope;

    public SomController(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services,
            TenantScope tenantScope) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.tenantScope = tenantScope;
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
        List<ServiceInstance> rows = relatedPartyId != null
                ? services.findByTenantIdAndOwnerPartyId(tenant, relatedPartyId)
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
        map.put("@type", "Service");
        return map;
    }
}
