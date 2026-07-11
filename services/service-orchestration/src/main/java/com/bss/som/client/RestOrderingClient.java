package com.bss.som.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class RestOrderingClient implements OrderingClient {

    private final RestClient restClient;

    public RestOrderingClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.ordering-base-url:http://localhost:8082}") String baseUrl) {
        // JDK factory: HttpURLConnection cannot send PATCH.
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public void complete(String productOrderId) {
        try {
            restClient.patch().uri("/tmf-api/productOrderingManagement/v4/productOrder/{id}", productOrderId)
                    .header("Content-Type", "application/json")
                    .body(Map.of("state", "completed"))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("ordering rejected the completion callback", e);
        }
    }
}
