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
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
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
}
