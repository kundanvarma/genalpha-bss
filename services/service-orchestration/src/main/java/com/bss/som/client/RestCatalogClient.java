package com.bss.som.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import static com.bss.som.mapper.Wire.idOf;

@Component
public class RestCatalogClient implements CatalogClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RestCatalogClient.class);

    private final RestClient restClient;
    /** Offerings don't change category mid-flight; cache per id, forever. */
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> chargingCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> nameCache = new ConcurrentHashMap<>();
    /** A spec's CFS is design-time data; cache per offering like the category. */
    /**
     * Step 3 makes the catalog the thing the orchestrator OBEYS, so a product
     * manager's edit must reach an order within a short while: the chain caches
     * (CFS, RFS list, spec characteristics) hold an entry for {@link #CATALOG_TTL_MS}
     * and then re-read. Design-time data changes rarely; when it does, the next
     * order after the window sees it — and the suite proves it.
     */
    public static final long CATALOG_TTL_MS = 10_000L;

    /** A cached read and when it was taken. */
    private record Stamped<T>(T value, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CATALOG_TTL_MS;
        }
    }

    private static <T> T freshOrNull(Map<String, Stamped<T>> cache, String key) {
        Stamped<T> s = cache.get(key);
        return s != null && s.fresh() ? s.value() : null;
    }

    private final Map<String, Stamped<Optional<Cfs>>> cfsCache = new ConcurrentHashMap<>();
    /** The RFS list under a CFS is design-time data too; cache per CFS. */
    private final Map<String, Stamped<List<Rfs>>> rfsCache = new ConcurrentHashMap<>();
    /** A product spec's characteristics are design-time data; cache per offering like the rest. */
    private final Map<String, Stamped<Map<String, String>>> specCharsCache = new ConcurrentHashMap<>();

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
            String specId = idOf(offering == null ? null : offering.get("productSpecification"));
            if (specId == null) {
                return java.util.List.of();
            }
            Map<String, Object> spec = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", specId)
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
    public Optional<Cfs> cfsOf(String offeringId) {
        if (offeringId == null) {
            return Optional.empty();
        }
        Optional<Cfs> cached = freshOrNull(cfsCache, offeringId);
        if (cached != null) {
            return cached;
        }
        Optional<Cfs> read = readCfs(offeringId);
        if (read != null) {
            cfsCache.put(offeringId, new Stamped<>(read, System.currentTimeMillis())); // only a successful read is worth keeping
        }
        return read == null ? Optional.empty() : read;
    }

    @SuppressWarnings("unchecked")
    private Optional<Cfs> readCfs(String id) {
            try {
                Map<String, Object> offering = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                        .retrieve().body(Map.class);
                String specId = idOf(offering == null ? null : offering.get("productSpecification"));
                if (specId == null) {
                    return Optional.empty();
                }
                Map<String, Object> spec = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", specId)
                        .retrieve().body(Map.class);
                if (spec == null || !(spec.get("serviceSpecification") instanceof List<?> refs) || refs.isEmpty()) {
                    return Optional.empty();
                }
                String cfsId = idOf(refs.get(0));
                if (cfsId == null) {
                    return Optional.empty();
                }
                Map<String, Object> cfs = restClient.get()
                        .uri("/tmf-api/serviceCatalogManagement/v4/serviceSpecification/{id}", cfsId)
                        .retrieve().body(Map.class);
                if (cfs == null) {
                    return Optional.empty();
                }
                String family = null;
                if (cfs.get("serviceSpecCharacteristic") instanceof List<?> chars) {
                    for (Object c : chars) {
                        if (c instanceof Map<?, ?> ch && "fulfilmentFamily".equals(String.valueOf(ch.get("name")))
                                && ch.get("serviceSpecCharacteristicValue") instanceof List<?> vals && !vals.isEmpty()
                                && vals.get(0) instanceof Map<?, ?> v0 && v0.get("value") != null) {
                            family = String.valueOf(v0.get("value")).trim().toLowerCase(java.util.Locale.ROOT);
                        }
                    }
                }
                if (family != null && !Cfs.FAMILIES.contains(family)) {
                    log.warn("catalog: CFS {} declares fulfilmentFamily '{}', not one of {} — ignoring it",
                            cfsId, family, Cfs.FAMILIES);
                    family = null;
                }
                String name = cfs.get("name") == null ? null : String.valueOf(cfs.get("name"));
                return Optional.of(new Cfs(cfsId, name, family));
            } catch (RuntimeException e) {
                log.warn("catalog: CFS unreadable for offering {}: {}", id, e.getMessage());
                return null;
            }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Rfs> rfsOf(String cfsId) {
        return rfsOfIfReadable(cfsId).orElse(List.of());
    }

    @Override
    public Optional<List<Rfs>> rfsOfIfReadable(String cfsId) {
        if (cfsId == null) {
            return Optional.of(List.of());
        }
        List<Rfs> cached = freshOrNull(rfsCache, cfsId);
        if (cached != null) {
            return Optional.of(cached);
        }
        List<Rfs> read = readRfs(cfsId);
        if (read == null) {
            return Optional.empty(); // unreadable: NOT cached — a starved catalog must not become "declares none" forever
        }
        rfsCache.put(cfsId, new Stamped<>(read, System.currentTimeMillis()));
        return Optional.of(read);
    }

    @SuppressWarnings("unchecked")
    private List<Rfs> readRfs(String id) {
            try {
                Map<String, Object> cfs = restClient.get()
                        .uri("/tmf-api/serviceCatalogManagement/v4/serviceSpecification/{id}", id)
                        .retrieve().body(Map.class);
                if (cfs == null || !(cfs.get("serviceSpecRelationship") instanceof List<?> rels)) {
                    return List.of();
                }
                List<Rfs> out = new java.util.ArrayList<>();
                for (Object r : rels) {
                    if (!(r instanceof Map<?, ?> rel)
                            || !"reliesOn".equalsIgnoreCase(String.valueOf(rel.get("relationshipType")))) {
                        continue;
                    }
                    String rfsId = idOf(rel);
                    if (rfsId == null) {
                        continue;
                    }
                    // what the CFS->RFS edge says the RFS consumes and whether it is
                    // required: characteristics on the relationship — the TMF633 shape
                    // (serviceSpecRelationshipCharacteristic, values in
                    // serviceSpecCharacteristicValue[].value, comma-separated or a list)
                    // or the plain {name, value} shape — both are read
                    List<String> consumes = new java.util.ArrayList<>();
                    boolean required = false;
                    for (String key : List.of("serviceSpecRelationshipCharacteristic", "characteristic")) {
                        if (!(rel.get(key) instanceof List<?> chars)) {
                            continue;
                        }
                        for (Object c : chars) {
                            if (!(c instanceof Map<?, ?> ch)) {
                                continue;
                            }
                            String chName = String.valueOf(ch.get("name"));
                            Object v = ch.get("value");
                            if (v == null && ch.get("serviceSpecCharacteristicValue") instanceof List<?> vals
                                    && !vals.isEmpty() && vals.get(0) instanceof Map<?, ?> v0) {
                                v = v0.get("value");
                            }
                            if ("consumes".equals(chName)) {
                                if (v instanceof List<?> vs) {
                                    vs.forEach(x -> consumes.add(String.valueOf(x).trim()));
                                } else if (v != null) {
                                    for (String x : String.valueOf(v).split(",")) {
                                        if (!x.isBlank()) {
                                            consumes.add(x.trim());
                                        }
                                    }
                                }
                            } else if ("required".equals(chName) && v != null) {
                                required = "true".equalsIgnoreCase(String.valueOf(v).trim());
                            }
                        }
                    }
                    Map<String, Object> rfs = restClient.get()
                            .uri("/tmf-api/serviceCatalogManagement/v4/serviceSpecification/{id}", rfsId)
                            .retrieve().body(Map.class);
                    if (rfs == null || !"RFS".equalsIgnoreCase(String.valueOf(rfs.get("serviceType")))) {
                        log.warn("catalog: CFS {} reliesOn {} which is not an RFS - ignoring it", id, rfsId);
                        continue;
                    }
                    String seam = characteristic(rfs.get("serviceSpecCharacteristic"), "seam");
                    // the TMF634 resource spec the RFS names: the standard reference list
                    // when the catalog carries it, else the house bridge characteristic
                    String resourceSpecId = null;
                    String resourceSpecName = null;
                    if (rfs.get("resourceSpecification") instanceof List<?> refs && !refs.isEmpty()) {
                        resourceSpecId = idOf(refs.get(0));
                        resourceSpecName = refs.get(0) instanceof Map<?, ?> m && m.get("name") != null
                                ? String.valueOf(m.get("name")) : null;
                    }
                    if (resourceSpecId == null) {
                        resourceSpecId = characteristic(rfs.get("serviceSpecCharacteristic"), "resourceSpecificationId");
                    }
                    if (resourceSpecId != null) {
                        try {
                            Map<String, Object> spec = restClient.get()
                                    .uri("/tmf-api/resourceCatalogManagement/v4/resourceSpecification/{id}", resourceSpecId)
                                    .retrieve().body(Map.class);
                            if (spec != null) {
                                if (spec.get("name") != null) {
                                    resourceSpecName = String.valueOf(spec.get("name"));
                                }
                                if (seam == null) {
                                    seam = characteristic(spec.get("resourceSpecCharacteristic"), "seam");
                                }
                            }
                        } catch (RuntimeException e) {
                            log.warn("catalog: resource spec {} unreadable for RFS {}: {}", resourceSpecId, rfsId, e.getMessage());
                        }
                    }
                    String name = rfs.get("name") == null ? null : String.valueOf(rfs.get("name"));
                    out.add(new Rfs(rfsId, name, seam == null ? null : seam.toLowerCase(java.util.Locale.ROOT),
                            List.copyOf(consumes), resourceSpecId, resourceSpecName, required));
                }
                return List.copyOf(out);
            } catch (RuntimeException e) {
                log.warn("catalog: RFS list unreadable for CFS {}: {}", id, e.getMessage());
                return null;
            }
    }

    /** The first value of a TMF spec characteristic by name (serviceSpecCharacteristicValue / resourceSpecCharacteristicValue / value). */
    private static String characteristic(Object chars, String name) {
        if (!(chars instanceof List<?> list)) {
            return null;
        }
        for (Object c : list) {
            if (c instanceof Map<?, ?> ch && name.equals(String.valueOf(ch.get("name")))) {
                for (String key : List.of("serviceSpecCharacteristicValue", "resourceSpecCharacteristicValue")) {
                    if (ch.get(key) instanceof List<?> vals && !vals.isEmpty()
                            && vals.get(0) instanceof Map<?, ?> v0 && v0.get("value") != null) {
                        return String.valueOf(v0.get("value")).trim();
                    }
                }
                if (ch.get("value") != null) {
                    return String.valueOf(ch.get("value")).trim();
                }
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> specCharacteristicsOf(String offeringId) {
        if (offeringId == null) {
            return Map.of();
        }
        Map<String, String> cached = freshOrNull(specCharsCache, offeringId);
        if (cached != null) {
            return cached;
        }
        Map<String, String> read = readSpecCharacteristics(offeringId);
        if (read != null) {
            specCharsCache.put(offeringId, new Stamped<>(read, System.currentTimeMillis()));
        }
        return read == null ? Map.of() : read;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readSpecCharacteristics(String id) {
            try {
                Map<String, Object> offering = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                        .retrieve().body(Map.class);
                String specId = idOf(offering == null ? null : offering.get("productSpecification"));
                if (specId == null) {
                    return Map.of();
                }
                Map<String, Object> spec = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", specId)
                        .retrieve().body(Map.class);
                Map<String, String> out = new java.util.LinkedHashMap<>();
                if (spec != null && spec.get("productSpecCharacteristic") instanceof List<?> chars) {
                    for (Object c : chars) {
                        if (c instanceof Map<?, ?> ch && ch.get("name") != null
                                && ch.get("productSpecCharacteristicValue") instanceof List<?> vals && !vals.isEmpty()
                                && vals.get(0) instanceof Map<?, ?> v0 && v0.get("value") != null) {
                            out.putIfAbsent(String.valueOf(ch.get("name")), String.valueOf(v0.get("value")));
                        }
                    }
                }
                return Map.copyOf(out);
            } catch (RuntimeException e) {
                log.warn("catalog: product spec characteristics unreadable for offering {}: {}", id, e.getMessage());
                return null;
            }
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
            String specId = idOf(offering == null ? null : offering.get("productSpecification"));
            if (specId == null) {
                return Optional.empty();
            }
            Map<String, Object> spec = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", specId)
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
                String specId = idOf(offering == null ? null : offering.get("productSpecification"));
                if (specId == null) {
                    return Optional.empty();
                }
                Map<String, Object> spec = restClient.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productSpecification/{id}", specId)
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
