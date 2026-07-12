package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestInventoryClient implements InventoryClient {

    private final RestClient restClient;

    public RestInventoryClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.inventory-base-url}") String baseUrl) {
        // JDK factory: HttpURLConnection cannot send PATCH (plan changes).
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public void createProduct(NewProduct product) {
        try {
            restClient.post()
                    .uri("/tmf-api/productInventory/v4/product")
                    .body(product)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("product-inventory rejected or is unreachable", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Optional<java.util.Map<String, Object>> getProduct(String id) {
        try {
            return java.util.Optional.ofNullable(restClient.get()
                    .uri("/tmf-api/productInventory/v4/product/{id}", id)
                    .retrieve()
                    .body(java.util.Map.class));
        } catch (RestClientException e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public void updateProduct(String id, java.util.Map<String, Object> patch) {
        try {
            restClient.patch()
                    .uri("/tmf-api/productInventory/v4/product/{id}", id)
                    .header("Content-Type", "application/json")
                    .body(patch)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("product-inventory rejected the plan change", e);
        }
    }
}
