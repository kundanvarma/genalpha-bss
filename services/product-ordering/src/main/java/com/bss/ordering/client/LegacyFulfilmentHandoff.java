package com.bss.ordering.client;

import com.bss.ordering.security.TenantRegistry;
import com.bss.ordering.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * THE OVERLAY SEAM, order side: when a tenant wraps a legacy estate and an
 * order carries a legacy-federated offering, the fulfilment HAND-OFF drops
 * a work order into the legacy queue — genalpha keeps the engagement
 * record, the legacy stack keeps fulfilment (never two writers). Fail-soft
 * and logged: a dead legacy queue never sinks the order; the miss is on
 * the record for reconciliation.
 */
@Component
public class LegacyFulfilmentHandoff {

    private static final Logger log = LoggerFactory.getLogger(LegacyFulfilmentHandoff.class);
    public static final String PREFIX = "legacy-";

    private final RestClient.Builder builder;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public LegacyFulfilmentHandoff(RestClient.Builder builder, TenantRegistry tenants,
            TenantScope tenantScope) {
        this.builder = builder;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    public void handoff(String orderId, List<Map<String, Object>> items, String customerPartyId) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        String base = tenant == null ? null : tenant.getLegacyFulfilmentBaseUrl();
        if (base == null || base.isBlank()) {
            return;
        }
        for (Map<String, Object> item : items) {
            Object off = item.get("productOffering");
            String offId = off instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : "";
            if (!offId.startsWith(PREFIX)) {
                continue;
            }
            try {
                builder.build().post().uri(base + "/api/createWorkOrder")
                        .header("Content-Type", "application/json")
                        .body(Map.of("ORDER_ID", orderId,
                                "PROD_CD", offId.substring(PREFIX.length()),
                                "CUST_NO", customerPartyId == null ? "" : customerPartyId))
                        .retrieve().toBodilessEntity();
                log.info("legacy hand-off: order {} item {} → legacy work-order queue", orderId, offId);
            } catch (Exception e) {
                log.warn("legacy hand-off FAILED for order {} item {} (order stands; reconcile): {}",
                        orderId, offId, e.getMessage());
            }
        }
    }
}
