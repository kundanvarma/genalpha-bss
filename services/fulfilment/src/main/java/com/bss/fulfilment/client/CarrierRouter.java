package com.bss.fulfilment.client;

import com.bss.fulfilment.entity.CarrierConfig;
import com.bss.fulfilment.service.CarrierConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the carrier for a booking. A tenant with a carrier menu books via its
 * default carrier's adapter (the shopper's own choice arrives in C-P3); a tenant
 * with no menu falls back to the global env carrier (Helthjem), so existing
 * deployments are unchanged. Fail-open throughout — any carrier error degrades to
 * the manual warehouse flow, exactly as the single-carrier seam did.
 */
@Component
public class CarrierRouter {

    private static final Logger log = LoggerFactory.getLogger(CarrierRouter.class);

    private final CarrierConfigService configs;
    private final CarrierRegistry registry;
    private final LogisticsClient fallback;   // the global env carrier

    public CarrierRouter(CarrierConfigService configs, CarrierRegistry registry, LogisticsClient fallback) {
        this.configs = configs;
        this.registry = registry;
        this.fallback = fallback;
    }

    public LogisticsClient.Booking book(String tenant, LogisticsClient.Booking request,
            DeliveryChoice delivery, String postcode) {
        // Precedence: the shopper's explicit carrier (C-P3, e.g. a pickup point's
        // carrier) → a postcode routing rule (C-P4) → the tenant default → global.
        Optional<CarrierConfig> chosen;
        if (delivery != null && delivery.carrier() != null) {
            chosen = configs.forTenantAndCarrier(tenant, delivery.carrier());
        } else {
            chosen = configs.routeByPostcode(tenant, postcode);
            if (chosen.isEmpty()) {
                chosen = configs.defaultForTenant(tenant);
            }
        }
        if (chosen.isEmpty()) {
            return fallback.book(request);                 // no per-tenant menu → global carrier
        }
        CarrierConfig cfg = chosen.get();
        CarrierAdapter adapter = registry.get(cfg.getCarrier());
        if (adapter == null) {
            log.warn("no adapter for configured carrier '{}' (tenant {}) — manual flow", cfg.getCarrier(), tenant);
            return null;
        }
        try {
            return adapter.book(cfg, request, delivery == null ? DeliveryChoice.HOME : delivery);
        } catch (Exception e) {
            log.warn("carrier {} book failed for shippingOrder {} (manual fallback): {}",
                    cfg.getCarrier(), request.shippingOrderId(), e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> pickupPoints(String tenant, String carrier, String postcode) {
        Optional<CarrierConfig> cfg = configs.forTenantAndCarrier(tenant, carrier);
        if (cfg.isEmpty()) {
            return List.of();
        }
        CarrierAdapter adapter = registry.get(carrier);
        return adapter == null ? List.of() : adapter.pickupPoints(cfg.get(), postcode);
    }

    /** The shopper's delivery menu for a postcode: every enabled carrier × its
     * methods, with pickup points inlined. Empty menu → the built-in home default. */
    public List<Map<String, Object>> deliveryOptions(String tenant, String postcode) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (CarrierConfig cfg : configs.enabledForTenant(tenant)) {
            CarrierAdapter adapter = registry.get(cfg.getCarrier());
            for (String method : parseMethods(cfg.getMethods())) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("method", method);
                opt.put("carrier", cfg.getCarrier());
                opt.put("carrierName", cfg.getDisplayName());
                if (("pickupPoint".equals(method) || "locker".equals(method)) && adapter != null) {
                    opt.put("points", adapter.pickupPoints(cfg, postcode));
                }
                options.add(opt);
            }
        }
        if (options.isEmpty()) {
            Map<String, Object> home = new LinkedHashMap<>();
            home.put("method", "home");
            home.put("carrier", null);
            home.put("carrierName", "Helthjem");
            options.add(home);
        }
        return options;
    }

    private List<String> parseMethods(String json) {
        if (json == null || json.isBlank()) {
            return List.of("home");
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of("home");
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
}
