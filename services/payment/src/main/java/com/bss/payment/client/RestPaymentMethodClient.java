package com.bss.payment.client;

import com.bss.payment.dto.VaultMethodRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;


@Component
@ConditionalOnProperty(name = "bss.paymentmethod.enabled", havingValue = "true", matchIfMissing = true)
public class RestPaymentMethodClient implements PaymentMethodClient {

    private final RestClient restClient;

    public RestPaymentMethodClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.payment-method-base-url:http://localhost:8103}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public JsonNode resolve(String paymentMethodId) {
        try {
            return restClient.get().uri("/tmf-api/paymentMethods/v4/paymentMethod/{id}", paymentMethodId)
                    .retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new IllegalStateException("payment-method vault is unreachable", e);
        }
    }

    @Override
    public JsonNode save(VaultMethodRequest request) {
        try {
            return restClient.post().uri("/tmf-api/paymentMethods/v4/paymentMethod")
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("payment-method vault is unreachable", e);
        }
    }
}
