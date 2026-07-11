package com.bss.payment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "bss.paymentmethod.enabled", havingValue = "true", matchIfMissing = true)
public class RestPaymentMethodClient implements PaymentMethodClient {

    private final RestClient restClient;

    public RestPaymentMethodClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.payment-method-base-url:http://localhost:8103}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(String paymentMethodId) {
        try {
            return restClient.get().uri("/tmf-api/paymentMethods/v4/paymentMethod/{id}", paymentMethodId)
                    .retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new IllegalStateException("payment-method vault is unreachable", e);
        }
    }
}
