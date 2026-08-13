package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.entity.WholesaleAccessOrder;
import com.bss.som.repository.WholesaleAccessOrderRepository;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wholesale/open-access face: the access-seeker orders we placed upstream to
 * fibre owners, so a back office (and wholesale billing) can see what we bought and
 * from whom. Read-only; service:read like the rest of the service-order face.
 */
@RestController
public class WholesaleAccessController {

    private final WholesaleAccessOrderRepository wholesaleOrders;
    private final TenantScope tenantScope;

    public WholesaleAccessController(WholesaleAccessOrderRepository wholesaleOrders, TenantScope tenantScope) {
        this.wholesaleOrders = wholesaleOrders;
        this.tenantScope = tenantScope;
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/wholesaleAccessOrder")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String productOrderId,
            @RequestParam(required = false) String state) {
        String tenant = tenantScope.currentTenantId();
        List<WholesaleAccessOrder> rows = productOrderId != null
                ? wholesaleOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)
                : state != null
                        ? wholesaleOrders.findByTenantIdAndState(tenant, state)
                        : wholesaleOrders.findByTenantId(tenant);
        return ResponseEntity.ok(rows.stream().map(this::view).toList());
    }

    private Map<String, Object> view(WholesaleAccessOrder w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("productOrderId", w.getProductOrderId());
        m.put("serviceId", w.getServiceId());
        m.put("accessOwner", w.getAccessOwner());
        m.put("accessLayer", w.getAccessLayer());
        m.put("bandwidthMbps", w.getBandwidthMbps());
        m.put("postCode", w.getPostCode());
        m.put("state", w.getState());
        m.put("externalId", w.getExternalId());
        m.put("activatedAt", w.getActivatedAt());
        m.put("createdAt", w.getCreatedAt());
        m.put("@type", "WholesaleAccessOrder");
        return m;
    }
}
