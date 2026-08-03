package com.bss.fulfilment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** The one machine write this service owns: completing a fulfilled order. */
@Component
public class OrderingClient {

    private final RestClient ordering;

    public OrderingClient(RestClient.Builder builder, MachineTokenInterceptor machineToken,
            @Value("${bss.downstream.ordering-base-url}") String orderingBase) {
        this.ordering = builder.baseUrl(orderingBase)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .requestInterceptor(machineToken).build();
    }

    public void completeOrder(String orderId) {
        ordering.patch()
                .uri("/tmf-api/productOrderingManagement/v4/productOrder/{id}", orderId)
                .body(Map.of("state", "completed"))
                .retrieve().toBodilessEntity();
    }
}
