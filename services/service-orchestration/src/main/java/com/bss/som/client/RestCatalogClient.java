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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RestCatalogClient.class);

    private final RestClient restClient;
    /** Offerings don't change category mid-flight; cache per id, forever. */
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> chargingCache = new ConcurrentHashMap<>();
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
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
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
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
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
        String cached = chargingCache.get(offeringId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return computeCharging(offeringId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.List<String> zeroRatedAppsOf(String offeringId) {
        if (offeringId == null) {
            return java.util.List.of();
        }
        try {
            Map<String, Object> offering = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
                    .retrieve().body(Map.class);
            Object specRef = offering == null ? null : offering.get("productSpecification");
            if (!(specRef instanceof Map<?, ?> ref) || ref.get("id") == null) {
                return java.util.List.of();
            }
            Map<String, Object> spec = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", String.valueOf(ref.get("id")))
                    .retrieve().body(Map.class);
            if (spec != null && spec.get("productSpecCharacteristic") instanceof List<?> chars) {
                for (Object c : chars) {
                    if (c instanceof Map<?, ?> ch && "zeroRatedApps".equals(String.valueOf(ch.get("name")))
                            && ch.get("productSpecCharacteristicValue") instanceof List<?> vals && !vals.isEmpty()
                            && vals.get(0) instanceof Map<?, ?> v0 && v0.get("value") != null) {
                        return java.util.Arrays.stream(String.valueOf(v0.get("value")).split(","))
                                .map(String::trim).filter(x -> !x.isEmpty()).toList();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("catalog: zero-rated apps unreadable for offering {}: {}", offeringId, e.getMessage());
        }
        return java.util.List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SliceIntent> sliceIntentOf(String offeringId) {
        if (offeringId == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> offering = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
                    .retrieve().body(Map.class);
            Object specRef = offering == null ? null : offering.get("productSpecification");
            if (!(specRef instanceof Map<?, ?> ref) || ref.get("id") == null) {
                return Optional.empty();
            }
            Map<String, Object> spec = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", String.valueOf(ref.get("id")))
                    .retrieve().body(Map.class);
            String profile = null;
            Integer hours = null;
            String chargingSpec = null;
            Integer guaranteed = null;
            if (spec != null && spec.get("productSpecCharacteristic") instanceof List<?> chars) {
                for (Object c : chars) {
                    if (!(c instanceof Map<?, ?> ch) || !(ch.get("productSpecCharacteristicValue") instanceof List<?> vals)
                            || vals.isEmpty() || !(vals.get(0) instanceof Map<?, ?> v0) || v0.get("value") == null) {
                        continue;
                    }
                    String name = String.valueOf(ch.get("name"));
                    if ("sliceProfile".equals(name)) {
                        profile = String.valueOf(v0.get("value"));
                    } else if ("boostHours".equals(name)) {
                        try {
                            hours = Integer.parseInt(String.valueOf(v0.get("value")).trim());
                        } catch (NumberFormatException ignored) {
                            // a non-numeric boostHours is treated as open-ended
                        }
                    } else if ("sliceChargingSpecId".equals(name)) {
                        chargingSpec = String.valueOf(v0.get("value")).trim();
                    } else if ("guaranteedDlMbps".equals(name)) {
                        try {
                            guaranteed = Integer.parseInt(String.valueOf(v0.get("value")).trim());
                        } catch (NumberFormatException ignored) {
                            // no number = no guarantee sold
                        }
                    }
                }
            }
            return profile == null ? Optional.empty() : Optional.of(new SliceIntent(profile, hours, chargingSpec, guaranteed));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    private Optional<String> computeCharging(String offeringId) {
        {
            try {
                Map<String, Object> offering = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
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
                            String val = String.valueOf(v.get("value"));
                            chargingCache.put(offeringId, val); // cache only a HIT
                            return Optional.of(val);
                        }
                    }
                }
                // a MISS is not cached: charging config may be seeded after
                // the first activation (the fresh-clone drill's lesson)
                return Optional.empty();
            } catch (RestClientException e) {
                return Optional.empty();
            }
        }
    }
}
