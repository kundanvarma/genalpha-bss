package com.bss.fulfilment.client;

import com.bss.fulfilment.entity.CarrierConfig;
import com.bss.fulfilment.service.CarrierConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public LogisticsClient.Booking book(String tenant, LogisticsClient.Booking request) {
        Optional<CarrierConfig> chosen = configs.defaultForTenant(tenant);
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
            return adapter.book(cfg, request);
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
}
