package com.bss.fulfilment.client;

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
 * The GENERIC carrier connector (console P4) — zero vendor code, mirroring the
 * CMS http connector: any carrier whose API takes a JSON booking and returns a
 * tracking reference rides on CONFIG alone. The config JSON maps the wire:
 *
 *   bookPath        POST target for a booking            (default /book)
 *   trackingPointer JSON pointer to the tracking ref     (default /trackingNumber)
 *   shipmentPointer JSON pointer to the shipment id      (default /carrierShipmentId)
 *   urlPointer      JSON pointer to a tracking URL       (default /trackingUrl)
 *   labelPointer    JSON pointer to a label ref          (default /labelRef)
 *   pickupPath      GET template, {postcode} substituted (default /pickup-points?postcode={postcode})
 *   pointsPointer   JSON pointer to the points array     (default "" = the root)
 *   authHeader      header name for the API key          (default Authorization)
 *   authPrefix      value prefix                         (default "Bearer ")
 *
 * The canonical booking body is the seam's own shape (shippingOrderId, tenantId,
 * callbackUrl, recipientPartyId, serviceLevel, pickupPoint?) — a provider that
 * needs a different body gets a NAMED adapter; this connector covers the long
 * tail that doesn't. Same honesty as the CMS one: carriers are safe to
 * generic-connect because a failed booking degrades to the manual warehouse
 * flow — money never rides this seam.
 */
@Component
public class HttpCarrierAdapter implements CarrierAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpCarrierAdapter.class);
    private final RestClient.Builder builder;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpCarrierAdapter(RestClient.Builder builder) {
        this.builder = builder;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public LogisticsClient.Booking book(CarrierConfig cfg, LogisticsClient.Booking r, DeliveryChoice delivery) {
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return null;
        }
        JsonNode c = configOf(cfg);
        boolean pickup = delivery != null && delivery.isPickup() && delivery.pickupPointId() != null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shippingOrderId", nz(r.shippingOrderId()));
        body.put("tenantId", nz(r.tenantId()));
        body.put("callbackUrl", nz(r.callbackUrl()));
        body.put("recipientPartyId", nz(r.recipientPartyId()));
        body.put("serviceLevel", pickup ? "PICKUP_POINT" : nz(r.serviceLevel()));
        if (pickup) {
            body.put("pickupPoint", delivery.pickupPointId());
        }
        String resp = client(cfg, c).post().uri(text(c, "bookPath", "/book"))
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(String.class);
        if (resp == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(resp);
            String tracking = at(root, text(c, "trackingPointer", "/trackingNumber"));
            if (tracking == null) {
                log.warn("http carrier '{}': no tracking ref at {} — manual flow",
                        cfg.getDisplayName(), text(c, "trackingPointer", "/trackingNumber"));
                return null;
            }
            String carrier = cfg.getDisplayName() != null ? cfg.getDisplayName() : "carrier";
            log.info("http carrier '{}' booked shippingOrder {} -> {}", carrier, r.shippingOrderId(), tracking);
            return new LogisticsClient.Booking(r.shippingOrderId(), r.tenantId(), r.callbackUrl(),
                    r.recipientPartyId(), r.serviceLevel(), carrier,
                    at(root, text(c, "shipmentPointer", "/carrierShipmentId")),
                    tracking,
                    at(root, text(c, "urlPointer", "/trackingUrl")),
                    at(root, text(c, "labelPointer", "/labelRef")));
        } catch (Exception e) {
            log.warn("http carrier '{}' response unreadable: {}", cfg.getDisplayName(), e.getMessage());
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> pickupPoints(CarrierConfig cfg, String postcode) {
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank() || postcode == null) {
            return List.of();
        }
        JsonNode c = configOf(cfg);
        try {
            String path = text(c, "pickupPath", "/pickup-points?postcode={postcode}")
                    .replace("{postcode}", postcode);
            String resp = client(cfg, c).get().uri(path).retrieve().body(String.class);
            JsonNode root = mapper.readTree(resp == null ? "[]" : resp);
            String pointer = text(c, "pointsPointer", "");
            JsonNode arr = pointer.isBlank() ? root : root.at(pointer);
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode p : arr) {
                    out.add(mapper.convertValue(p, Map.class));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private RestClient client(CarrierConfig cfg, JsonNode c) {
        RestClient.Builder b = builder.baseUrl(cfg.getBaseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String key = cfg.getSecretRef() == null ? null : System.getenv(cfg.getSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader(text(c, "authHeader", "Authorization"),
                    text(c, "authPrefix", "Bearer ") + key);
        }
        return b.build();
    }

    private JsonNode configOf(CarrierConfig cfg) {
        try {
            return cfg.getConfig() == null ? mapper.createObjectNode() : mapper.readTree(cfg.getConfig());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String text(JsonNode c, String key, String dflt) {
        JsonNode n = c.get(key);
        return n == null || n.isNull() || n.asText().isBlank() ? dflt : n.asText();
    }

    private static String at(JsonNode root, String pointer) {
        JsonNode n = root.at(pointer);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
