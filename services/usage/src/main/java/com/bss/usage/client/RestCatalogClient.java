package com.bss.usage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RestCatalogClient implements CatalogClient {

    private record Cached(Map<String, String> levers, long readAt) {
    }

    private static final long TTL_MS = 30_000;

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RestCatalogClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Cached for 30s per offering — product levers are read-mostly, but an
     * operator's config change must show up without a restart. Fails to
     * EMPTY: no characteristics means the coded defaults apply. */
    @Override
    public Map<String, String> specCharacteristicsOf(String offeringId) {
        if (offeringId == null) {
            return Map.of();
        }
        Cached hit = cache.get(offeringId);
        if (hit != null && System.currentTimeMillis() - hit.readAt() < TTL_MS) {
            return hit.levers();
        }
        Map<String, String> fresh = fetch(offeringId);
        cache.put(offeringId, new Cached(fresh, System.currentTimeMillis()));
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fetch(String offeringId) {
        try {
            Map<String, Object> offering = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
                    .retrieve().body(Map.class);
            Object specRef = offering == null ? null : offering.get("productSpecification");
            if (!(specRef instanceof Map<?, ?> ref) || ref.get("id") == null) {
                return Map.of();
            }
            Map<String, Object> spec = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}",
                            String.valueOf(ref.get("id")))
                    .retrieve().body(Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            if (spec != null && spec.get("productSpecCharacteristic") instanceof List<?> chars) {
                for (Object o : chars) {
                    if (o instanceof Map<?, ?> c && c.get("name") != null
                            && c.get("productSpecCharacteristicValue") instanceof List<?> values
                            && !values.isEmpty() && values.get(0) instanceof Map<?, ?> v
                            && v.get("value") != null) {
                        out.put(String.valueOf(c.get("name")), String.valueOf(v.get("value")));
                    }
                }
            }
            return Map.copyOf(out);
        } catch (RestClientException e) {
            return Map.of();
        }
    }
}
