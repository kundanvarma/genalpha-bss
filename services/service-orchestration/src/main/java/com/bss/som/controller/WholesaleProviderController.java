package com.bss.som.controller;

import com.bss.som.entity.ProviderAccessOrder;
import com.bss.som.service.WholesaleProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our MEF LSO Sonata Service Ordering face, as a fibre OWNER: retailers place
 * access-seeker orders here. Anonymous — the retailer is an external operator, not a
 * fleet identity; the tenant is carried on X-Tenant-Id (resolved and validated by
 * TenantScope, so a caller can only reach a tenant that exists). We record the
 * order, provision it on our own clock, and notify their callback.
 */
@RestController
public class WholesaleProviderController {

    private final WholesaleProviderService provider;

    public WholesaleProviderController(WholesaleProviderService provider) {
        this.provider = provider;
    }

    @PostMapping("/mefApi/serviceOrdering/v1/serviceOrder")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> order(@RequestBody Map<String, Object> body) {
        String buyerRef = str(body.get("externalId"));
        String callbackUrl = str(body.get("callbackUrl"));
        String retailerPartyId = str(body.get("buyerId"));
        String accessLayer = null, postCode = null;
        Integer bandwidth = null;
        if (body.get("serviceOrderItem") instanceof List<?> items && !items.isEmpty()
                && items.get(0) instanceof Map<?, ?> item
                && item.get("service") instanceof Map<?, ?> service
                && service.get("serviceCharacteristic") instanceof List<?> chars) {
            for (Object c : chars) {
                if (!(c instanceof Map<?, ?> ch)) {
                    continue;
                }
                String name = str(ch.get("name"));
                Object value = ch.get("value");
                if ("accessLayer".equals(name)) {
                    accessLayer = str(value);
                } else if ("postCode".equals(name)) {
                    postCode = str(value);
                } else if ("bandwidthMbps".equals(name) && value instanceof Number n) {
                    bandwidth = n.intValue();
                }
            }
        }
        ProviderAccessOrder o = provider.acceptOrder(buyerRef, callbackUrl, retailerPartyId,
                accessLayer, bandwidth, postCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", o.getId());
        out.put("state", o.getState());
        out.put("@type", "ServiceOrder");
        return ResponseEntity.status(201).body(out);
    }

    /** What we have sold — the provider's order book (for the partner portal + billing). */
    @GetMapping("/tmf-api/serviceOrdering/v4/providerAccessOrder")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(provider.list().stream().map(this::view).toList());
    }

    /** What each retailer owes US for the access live on our network — the wholesale
     *  bill we raise as the fibre owner (accounts receivable). */
    @GetMapping("/tmf-api/serviceOrdering/v4/wholesaleProviderSettlement")
    public ResponseEntity<Map<String, Object>> settlement() {
        List<Map<String, Object>> retailers = provider.providerSettlement();
        double total = retailers.stream()
                .mapToDouble(r -> ((Number) r.getOrDefault("totalMonthlyCharge", 0)).doubleValue()).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "WholesaleProviderSettlement");
        out.put("periodType", "month");
        out.put("retailer", retailers);
        out.put("totalMonthlyRevenue", Math.round(total * 100.0) / 100.0);
        out.put("currency", "EUR");
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> view(ProviderAccessOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("buyerRef", o.getBuyerRef());
        m.put("retailerPartyId", o.getRetailerPartyId());
        m.put("accessLayer", o.getAccessLayer());
        m.put("bandwidthMbps", o.getBandwidthMbps());
        m.put("postCode", o.getPostCode());
        m.put("state", o.getState());
        m.put("activatedAt", o.getActivatedAt());
        m.put("createdAt", o.getCreatedAt());
        m.put("@type", "ProviderAccessOrder");
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
