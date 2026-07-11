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
        return partyId -> {
            try {
                return rest.get()
                        .uri("/tmf-api/productInventory/v4/product?relatedPartyId={p}&limit=100", partyId)
                        .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                        });
            } catch (RestClientException e) {
                throw new IllegalStateException("product-inventory is unreachable", e);
            }
        };
    }
}
