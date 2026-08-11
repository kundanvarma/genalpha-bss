package com.bss.fulfilment.client;

import com.bss.fulfilment.entity.CarrierConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Helthjem wire (POST /shipments, poll + callback) as a per-tenant adapter — the
 * same shape the global RestLogisticsClient speaks, but configured from the
 * tenant's carrier_config row instead of a deployment env.
 */
@Component
public class HelthjemCarrierAdapter implements CarrierAdapter {

    private static final Logger log = LoggerFactory.getLogger(HelthjemCarrierAdapter.class);

    private final RestClient.Builder builder;

    public HelthjemCarrierAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "helthjem";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LogisticsClient.Booking book(CarrierConfig cfg, LogisticsClient.Booking r) {
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return null;
        }
        RestClient http = builder.baseUrl(cfg.getBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).build();
        Map<String, Object> resp = http.post().uri("/shipments")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "shippingOrderId", nz(r.shippingOrderId()),
                        "tenantId", nz(r.tenantId()),
                        "callbackUrl", nz(r.callbackUrl()),
                        "recipientPartyId", nz(r.recipientPartyId()),
                        "serviceLevel", nz(r.serviceLevel())))
                .retrieve().body(Map.class);
        if (resp == null) {
            return null;
        }
        String carrier = cfg.getDisplayName() != null ? cfg.getDisplayName() : str(resp.get("carrier"));
        log.info("helthjem booked shippingOrder {} -> {}", r.shippingOrderId(), resp.get("trackingNumber"));
        return new LogisticsClient.Booking(r.shippingOrderId(), r.tenantId(), r.callbackUrl(),
                r.recipientPartyId(), r.serviceLevel(), carrier, str(resp.get("carrierShipmentId")),
                str(resp.get("trackingNumber")), str(resp.get("trackingUrl")), str(resp.get("labelRef")));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
