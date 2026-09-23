package com.bss.fulfilment.client;

import com.bss.fulfilment.dto.PickupPoint;
import com.bss.fulfilment.entity.CarrierConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bring/Posten wire — the second real carrier, and the one that unlocks PICKUP
 * POINTS (a first-class Nordic delivery method). Booking API
 * (POST booking/api/booking → consignment number) and Pickup Point API
 * (GET pickuppoint/{country}/postalCode/{postcode}.json → nearby points). Shaped
 * to Bring's documented wire; proven against a Bring-shaped mock, real creds opt-in.
 */
@Component
public class BringCarrierAdapter implements CarrierAdapter {

    private static final Logger log = LoggerFactory.getLogger(BringCarrierAdapter.class);

    private final RestClient.Builder builder;
    private final ObjectMapper mapper = new ObjectMapper();

    public BringCarrierAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "bring";
    }

    @Override
    @SuppressWarnings("unchecked")
    public LogisticsClient.Booking book(CarrierConfig cfg, LogisticsClient.Booking r, DeliveryChoice delivery) {
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return null;
        }
        // A pickup-point booking sets the pickupPoint id (Bring's 'parties.pickupPoint');
        // home delivery omits it. serviceLevel carries the method for the mock's log.
        boolean pickup = delivery != null && delivery.isPickup() && delivery.pickupPointId() != null;
        // a LinkedHashMap seeded from Map.of keeps Map.of's per-JVM order: put the keys in order
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shippingOrderId", nz(r.shippingOrderId()));
        body.put("tenantId", nz(r.tenantId()));
        body.put("callbackUrl", nz(r.callbackUrl()));
        body.put("recipientPartyId", nz(r.recipientPartyId()));
        body.put("serviceLevel", pickup ? "PICKUP_POINT" : nz(r.serviceLevel()));
        if (pickup) {
            body.put("pickupPoint", delivery.pickupPointId());
        }
        Map<String, Object> resp = client(cfg).post().uri("/booking/api/booking")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(Map.class);
        if (resp == null) {
            return null;
        }
        String carrier = cfg.getDisplayName() != null ? cfg.getDisplayName() : "Posten/Bring";
        log.info("bring booked shippingOrder {} -> consignment {}", r.shippingOrderId(),
                resp.get("trackingNumber"));
        return new LogisticsClient.Booking(r.shippingOrderId(), r.tenantId(), r.callbackUrl(),
                r.recipientPartyId(), r.serviceLevel(), carrier, str(resp.get("carrierShipmentId")),
                str(resp.get("trackingNumber")), str(resp.get("trackingUrl")), str(resp.get("labelRef")));
    }

    @Override
    public List<JsonNode> pickupPoints(CarrierConfig cfg, String postcode) {
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank() || postcode == null) {
            return List.of();
        }
        try {
            String resp = client(cfg).get()
                    .uri("/pickuppoint/{country}/postalCode/{pc}.json", "NO", postcode)
                    .retrieve().body(String.class);
            JsonNode points = mapper.readTree(resp == null ? "{}" : resp).path("pickupPoint");
            List<JsonNode> out = new ArrayList<>();
            if (points.isArray()) {
                for (JsonNode p : points) {
                    if (p.isObject()) {
                        // Bring's own document normalised to the four facts a shopper picks from;
                        // a key Bring does not send is written as null, as the map wrote it
                        out.add(mapper.valueToTree(new PickupPoint(node(p, "id"), node(p, "name"),
                                node(p, "address"), node(p, "openingHours"))));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("bring pickup-point lookup failed for {}: {}", postcode, e.getMessage());
            return List.of();
        }
    }

    private static JsonNode node(JsonNode p, String key) {
        return p.has(key) ? p.get(key) : null;
    }

    private RestClient client(CarrierConfig cfg) {
        RestClient.Builder b = builder.baseUrl(cfg.getBaseUrl());
        String key = cfg.getSecretRef() == null ? null : System.getenv(cfg.getSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("X-MyBring-API-Key", key);
        }
        return b.build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
