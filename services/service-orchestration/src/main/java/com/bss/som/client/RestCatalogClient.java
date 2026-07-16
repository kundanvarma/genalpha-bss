package com.bss.som.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RestCatalogClient implements CatalogClient {

    private final RestClient restClient;
    /** Offerings don't change category mid-flight; cache per id, forever. */
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> chargingCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> nameCache = new ConcurrentHashMap<>();

    public RestCatalogClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> categoryOf(String offeringId) {
        if (offeringId == null) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(offeringId, id -> {
            try {
                Map<String, Object> offering = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                        .retrieve().body(Map.class);
                if (offering != null && offering.get("category") instanceof List<?> cats
                        && !cats.isEmpty() && cats.get(0) instanceof Map<?, ?> c && c.get("name") != null) {
                    return Optional.of(String.valueOf(c.get("name")));
                }
                return Optional.empty();
            } catch (RestClientException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> nameOf(String offeringId) {
        if (offeringId == null) {
            return Optional.empty();
        }
        return nameCache.computeIfAbsent(offeringId, id -> {
            try {
                Map<String, Object> offering = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                        .retrieve().body(Map.class);
                return offering == null || offering.get("name") == null
                        ? Optional.empty() : Optional.of(String.valueOf(offering.get("name")));
            } catch (RestClientException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> chargingSpecOf(String offeringId) {
        if (offeringId == null) {
            return Optional.empty();
        }
        return chargingCache.computeIfAbsent(offeringId, id -> {
            try {
                Map<String, Object> offering = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                        .retrieve().body(Map.class);
                Object specRef = offering == null ? null : offering.get("productSpecification");
                if (!(specRef instanceof Map<?, ?> ref) || ref.get("id") == null) {
                    return Optional.empty();
                }
                Map<String, Object> spec = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}",
                                String.valueOf(ref.get("id")))
                        .retrieve().body(Map.class);
                if (spec != null && spec.get("productSpecCharacteristic") instanceof List<?> chars) {
                    for (Object o : chars) {
                        if (o instanceof Map<?, ?> c && "chargingSpecId".equals(c.get("name"))
                                && c.get("productSpecCharacteristicValue") instanceof List<?> values
                                && !values.isEmpty() && values.get(0) instanceof Map<?, ?> v
                                && v.get("value") != null) {
                            return Optional.of(String.valueOf(v.get("value")));
                        }
                    }
                }
                return Optional.empty();
            } catch (RestClientException e) {
                return Optional.empty();
            }
        });
    }
}
