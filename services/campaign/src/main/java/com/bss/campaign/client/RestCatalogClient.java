package com.bss.campaign.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attributed revenue = the monthly recurring price of what was ordered,
 * read from the tenant's own catalog (the same public face the shop
 * uses; X-Tenant-Id picks the tenant, exactly like an anonymous
 * storefront request). Values cache for 30s per offering; any failure
 * counts as zero — a catalog hiccup must never block a conversion, it
 * only under-reports the money.
 */
@Component
public class RestCatalogClient implements CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(RestCatalogClient.class);
    private static final long CACHE_MS = 30_000;

    private record CachedValue(BigDecimal value, Instant fetchedAt) {
    }

    private final RestClient restClient;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public RestCatalogClient(RestClient.Builder builder,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public BigDecimal monthlyValueOf(String tenantId, List<String> offeringIds) {
        BigDecimal total = BigDecimal.ZERO;
        for (String offeringId : offeringIds == null ? List.<String>of() : offeringIds) {
            total = total.add(monthlyOf(tenantId, offeringId));
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal monthlyOf(String tenantId, String offeringId) {
        String key = tenantId + ":" + offeringId;
        CachedValue cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plusMillis(CACHE_MS).isAfter(Instant.now())) {
            return cached.value();
        }
        BigDecimal monthly = BigDecimal.ZERO;
        try {
            Map<String, Object> offering = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", offeringId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve().body(Map.class);
            if (offering != null && offering.get("productOfferingPrice") instanceof List<?> refs) {
                for (Object ref : refs) {
                    if (!(ref instanceof Map<?, ?> r) || r.get("id") == null) {
                        continue;
                    }
                    Map<String, Object> price = restClient.get()
                            .uri("/tmf-api/productCatalogManagement/v4/productOfferingPrice/{id}",
                                    String.valueOf(r.get("id")))
                            .header("X-Tenant-Id", tenantId)
                            .retrieve().body(Map.class);
                    if (price != null && "recurring".equals(price.get("priceType"))
                            && "month".equals(price.get("recurringChargePeriodType"))
                            && price.get("price") instanceof Map<?, ?> p && p.get("value") != null) {
                        monthly = monthly.add(new BigDecimal(String.valueOf(p.get("value"))));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("catalog value lookup failed for {} — attributing zero: {}",
                    offeringId, e.getMessage());
        }
        cache.put(key, new CachedValue(monthly, Instant.now()));
        return monthly;
    }
}
