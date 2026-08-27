package com.bss.basemigration.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-side view of TMF637 product inventory: the installed base a plan
 * moves. The inventory API filters by status/party, not by offering, so
 * discovery pages the active base and matches offerings client-side.
 * Machine call under this service's own identity (inventory:read).
 */
@Component
public class InventoryClient {

    private static final int PAGE = 100;
    private static final int MAX_PAGES = 100; // 10k products — a demo-scale ceiling, stated honestly

    private final RestClient rest;

    public InventoryClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.inventory-base-url:http://localhost:8083}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Every ACTIVE product in the acting tenant, paged to completion. */
    public List<Map<String, Object>> listActiveProducts() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Map<String, Object>> batch = rest.get()
                    .uri("/tmf-api/productInventory/v4/product?status=active&offset={o}&limit={l}",
                            page * PAGE, PAGE)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < PAGE) {
                break;
            }
        }
        return all;
    }

    /** One party's active products (the age/promo scanners work per party). */
    public List<Map<String, Object>> activeProductsOf(String partyId) {
        List<Map<String, Object>> batch = rest.get()
                .uri("/tmf-api/productInventory/v4/product?status=active&relatedPartyId={p}&limit={l}",
                        partyId, PAGE)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return batch == null ? List.of() : batch;
    }

    /** One installed product by id, for snapshots and rollback verification. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> getProduct(String productId) {
        try {
            return Optional.ofNullable(rest.get()
                    .uri("/tmf-api/productInventory/v4/product/{id}", productId)
                    .retrieve()
                    .body(Map.class));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
