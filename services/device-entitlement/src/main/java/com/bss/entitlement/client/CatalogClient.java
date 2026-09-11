package com.bss.entitlement.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plan's product specification decides the entitlements: characteristics
 * such as {@code volte}, {@code vowifi}, {@code smsoip}, {@code vonr},
 * {@code companionEsim}, {@code esimTransfer}, {@code dataPlanType} live on the
 * spec of the offering the line was sold under. Read through TMF620 with the
 * component's machine identity; cached briefly so a fleet of phones checking
 * in does not hammer the catalog.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);
    private static final long TTL_MS = 60_000;

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Map<String, String> chars, long at) { }

    public CatalogClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Spec characteristics of an offering as name → first value (lower-cased names). Empty when unknown. */
    @SuppressWarnings("unchecked")
    public Map<String, String> planCharacteristics(String tenantId, String offeringId) {
        if (offeringId == null || offeringId.isBlank()) {
            return Map.of();
        }
        String key = tenantId + "|" + offeringId;
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < TTL_MS) {
            return c.chars();
        }
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Map<String, Object> offering = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
                    .retrieve().body(Map.class);
            Object specRef = offering == null ? null : offering.get("productSpecification");
            if (specRef instanceof Map<?, ?> ref && ref.get("id") != null) {
                Map<String, Object> spec = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", ref.get("id"))
                        .retrieve().body(Map.class);
                Object chars = spec == null ? null : spec.get("productSpecCharacteristic");
                if (chars instanceof List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> ch) || ch.get("name") == null) {
                            continue;
                        }
                        Object values = ch.get("productSpecCharacteristicValue");
                        if (values instanceof List<?> vs && !vs.isEmpty() && vs.get(0) instanceof Map<?, ?> v0
                                && v0.get("value") != null) {
                            out.put(String.valueOf(ch.get("name")).toLowerCase(), String.valueOf(v0.get("value")));
                        }
                    }
                }
            }
            if (offering != null && offering.get("name") != null) {
                out.put("_offeringname", String.valueOf(offering.get("name")));
            }
        } catch (RuntimeException e) {
            log.warn("catalog unreachable for offering {} ({}) — entitlements fall back to the line's overrides only",
                    offeringId, e.getMessage());
            return c != null ? c.chars() : Map.of();
        }
        cache.put(key, new Cached(out, System.currentTimeMillis()));
        return out;
    }
}
