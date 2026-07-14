package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Component
public class RestCatalogClient implements CatalogClient {

    private final RestClient restClient;

    public RestCatalogClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public Optional<OfferingRef> findOffering(String id) {
        try {
            OfferingRef offering = restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                    .retrieve()
                    .body(OfferingRef.class);
            return Optional.ofNullable(offering);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new DownstreamException("product-catalog is unreachable", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<java.util.Map<String, Object>> findOfferingDetail(String id) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOffering/{id}", id)
                    .retrieve()
                    .body(java.util.Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new DownstreamException("product-catalog is unreachable", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<java.util.Map<String, Object>> findPrice(String id) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/tmf-api/productCatalogManagement/v4/productOfferingPrice/{id}", id)
                    .retrieve()
                    .body(java.util.Map.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new DownstreamException("product-catalog is unreachable", e);
        }
    }
}
