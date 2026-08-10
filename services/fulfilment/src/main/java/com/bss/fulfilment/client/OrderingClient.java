package com.bss.fulfilment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** The one machine write this service owns: completing a fulfilled order. */
@Component
public class OrderingClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderingClient.class);

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

    /**
     * C2 — per-item completion. A delivered parcel (or a done install) completes
     * just ITS order item; ordering rolls the parent order up. Fail-soft: a
     * missing item id or a hiccup must not stop the other items completing.
     */
    public void updateItemState(String orderId, String itemId, String state) {
        if (itemId == null) {
            return;
        }
        try {
            ordering.patch()
                    .uri("/tmf-api/productOrderingManagement/v4/productOrder/{id}/productOrderItem/{itemId}",
                            orderId, itemId)
                    .body(Map.of("state", state))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("fulfilment: item-state callback failed for order {} item {} -> {}: {}",
                    orderId, itemId, state, e.getMessage());
        }
    }
}
