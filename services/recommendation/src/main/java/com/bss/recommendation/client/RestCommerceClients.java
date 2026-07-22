package com.bss.recommendation.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Configuration
public class RestCommerceClients {

    private RestClient client(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            String baseUrl) {
        return builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Bean
    CommerceClients.InsightClient insightClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.insight-base-url:http://localhost:8119}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return (partyId) -> {
            // fail-open: no insight component, no consent, no interests — the
            // popularity ranking stands on its own
            try {
                java.util.Map<String, Object> profile = rest.get()
                        .uri("/insight/v1/partyProfile?partyId=" + partyId)
                        .retrieve().body(java.util.Map.class);
                Object interests = profile == null ? null : profile.get("interests");
                return interests instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();
            } catch (Exception e) {
                return List.of();
            }
        };
    }

    @Bean
    CommerceClients.CatalogClient catalogClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return () -> {
            try {
                return rest.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOffering?limit=100&lifecycleStatus=Active")
                        .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                        });
            } catch (RestClientException e) {
                throw new IllegalStateException("product-catalog is unreachable", e);
            }
        };
    }

    @Bean
    CommerceClients.InventoryClient inventoryClient(RestClient.Builder builder,
            MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.inventory-base-url:http://localhost:8083}") String baseUrl) {
        RestClient rest = client(builder, tokenInterceptor, baseUrl);
        return new CommerceClients.InventoryClient() {
            @Override
            public List<Map<String, Object>> productsOf(String partyId) {
                try {
                    return rest.get()
                            .uri("/tmf-api/productInventory/v4/product?relatedPartyId={p}&limit=100", partyId)
                            .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                            });
                } catch (RestClientException e) {
                    throw new IllegalStateException("product-inventory is unreachable", e);
                }
            }

            @Override
            public List<Map<String, Object>> allProducts() {
                // PAGE THROUGH ALL of them: a single page silently caps the
                // adoption/affinity signal to a sample (a busy tenant has
                // thousands). TMF caps limit at 100, so walk offsets until a
                // short page. Cached by the callers, so this runs on a miss.
                try {
                    List<Map<String, Object>> all = new java.util.ArrayList<>();
                    for (int offset = 0; offset < 100_000; offset += 100) {
                        List<Map<String, Object>> page = rest.get()
                                .uri("/tmf-api/productInventory/v4/product?limit=100&offset={o}", offset)
                                .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                                });
                        if (page == null || page.isEmpty()) {
                            break;
                        }
                        all.addAll(page);
                        if (page.size() < 100) {
                            break;
                        }
                    }
                    return all;
                } catch (RestClientException e) {
                    throw new IllegalStateException("product-inventory is unreachable", e);
                }
            }
        };
    }
}
