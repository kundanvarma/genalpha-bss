package com.bss.fulfilment.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * REST adapter for the logistics seam. Follows the house seam convention (see
 * the OCS seam): a BLANK base URL disables it — every call becomes a logged
 * no-op — and any carrier error is swallowed with a warn, never propagated. So
 * the seam can be switched on per-tenant (a carrier URL) without touching code,
 * and a carrier outage degrades to the manual warehouse flow.
 */
@Component
public class RestLogisticsClient implements LogisticsClient {

    private static final Logger log = LoggerFactory.getLogger(RestLogisticsClient.class);

    private final boolean enabled;
    private final String carrier;
    private final RestClient http;

    public RestLogisticsClient(RestClient.Builder builder,
            @Value("${bss.downstream.logistics-base-url:}") String baseUrl,
            @Value("${bss.downstream.logistics-carrier:Helthjem}") String carrier) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.carrier = carrier;
        this.http = enabled ? builder.baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .build() : null;
        log.info("logistics seam {} (carrier={})", enabled ? "ENABLED" : "disabled (no base url)", carrier);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Booking book(Booking r) {
        if (!enabled) {
            return null;
        }
        try {
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
            return new Booking(r.shippingOrderId(), r.tenantId(), r.callbackUrl(), r.recipientPartyId(),
                    r.serviceLevel(), str(resp.get("carrier")), str(resp.get("carrierShipmentId")),
                    str(resp.get("trackingNumber")), str(resp.get("trackingUrl")), str(resp.get("labelRef")));
        } catch (Exception e) {
            log.warn("logistics book failed for shippingOrder {} (manual fallback): {}",
                    r.shippingOrderId(), e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Tracking track(String trackingNumber) {
        if (!enabled || trackingNumber == null) {
            return null;
        }
        try {
            Map<String, Object> resp = http.get()
                    .uri("/shipments/{tn}/tracking", trackingNumber)
                    .retrieve().body(Map.class);
            return resp == null ? null
                    : new Tracking(str(resp.get("status")), str(resp.get("carrierStatus")));
        } catch (Exception e) {
            log.warn("logistics track failed for {}: {}", trackingNumber, e.getMessage());
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
