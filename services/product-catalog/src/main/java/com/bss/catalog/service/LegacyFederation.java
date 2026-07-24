package com.bss.catalog.service;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.security.TenantRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE OVERLAY SEAM: a tenant with a legacy BSS federates that estate's
 * catalog through here — read-through, cached, fail-soft, mapped from
 * whatever old shape the legacy speaks into TMF620 on the way out. The
 * legacy stays the master (never two writers); genalpha is the
 * merchandising face. Ids wear the `legacy-` prefix so every downstream
 * (ordering hand-off, the ACP feed) knows which estate a row came from.
 */
@Component
public class LegacyFederation {

    public static final String PREFIX = "legacy-";

    private final RestClient.Builder builder;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private record CacheEntry(long at, List<ProductOfferingDto> items) { }

    public LegacyFederation(RestClient.Builder builder) {
        this.builder = builder;
    }

    @SuppressWarnings("unchecked")
    public List<ProductOfferingDto> offeringsFor(TenantRegistry.TenantEntry tenant) {
        if (tenant == null || tenant.getLegacyCatalogBaseUrl() == null
                || tenant.getLegacyCatalogBaseUrl().isBlank()) {
            return List.of();
        }
        CacheEntry hit = cache.get(tenant.getId());
        if (hit != null && System.currentTimeMillis() - hit.at() < 30_000) {
            return hit.items();
        }
        try {
            Map<String, Object> env = builder.build().get()
                    .uri(tenant.getLegacyCatalogBaseUrl() + "/api/getProductList")
                    .retrieve().body(Map.class);
            List<Map<String, Object>> rows = (List<Map<String, Object>>)
                    ((Map<String, Object>) env.getOrDefault("resultSet", Map.of()))
                            .getOrDefault("row", List.of());
            List<ProductOfferingDto> mapped = rows.stream().map(this::map).toList();
            cache.put(tenant.getId(), new CacheEntry(System.currentTimeMillis(), mapped));
            return mapped;
        } catch (Exception e) {
            // fail-soft: a slow or dead legacy never breaks the native catalog
            return hit == null ? List.of() : hit.items();
        }
    }

    public ProductOfferingDto byId(TenantRegistry.TenantEntry tenant, String id) {
        return offeringsFor(tenant).stream()
                .filter(o -> id.equals(o.getId())).findFirst().orElse(null);
    }

    private ProductOfferingDto map(Map<String, Object> row) {
        ProductOfferingDto dto = new ProductOfferingDto();
        String code = String.valueOf(row.get("PROD_CD"));
        dto.setId(PREFIX + code);
        dto.setName(String.valueOf(row.get("PROD_NM")));
        dto.setDescription("Federated from the legacy estate (read-through; legacy is the master)");
        dto.setLifecycleStatus("Active");
        dto.setCategory(List.of(Map.of("id", "legacy", "name", "Legacy estate")));
        // price embedded on the ref: the feed's pickPrice falls back to it
        dto.setProductOfferingPrice(List.of(Map.of(
                "id", PREFIX + "price-" + code,
                "priceType", "oneTime",
                "price", Map.of("value", Double.parseDouble(String.valueOf(row.get("PRICE_AMT"))),
                        "unit", String.valueOf(row.getOrDefault("CURR_CD", "EUR"))))));
        return dto;
    }
}
