package com.bss.basemigration.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's ONE write into production: a TMF622 modify order per
 * subscriber, exactly the shape the storefront's plan change sends —
 * the item names the installed product and the offering to move it to,
 * and the ordering service's own validation, proration and completion
 * path do the rest, untouched. Machine call under this service's own
 * identity (ordering:write); relatedParty names the customer because a
 * machine token is not party-scoped.
 */
@Component
public class OrderingClient {

    private final RestClient rest;

    public OrderingClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.ordering-base-url:http://localhost:8082}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /**
     * Place the plan-change order. Characteristics (carry-over map) ride on
     * the item's product when present. Returns the created order (the
     * ordering service completes modify-only orders inline).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> placeModifyOrder(String partyId, String productId,
            String offeringId, String offeringName, Map<String, Object> characteristics,
            String description) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", productId);
        if (characteristics != null && !characteristics.isEmpty()) {
            List<Map<String, Object>> chars = new ArrayList<>();
            characteristics.forEach((name, value) -> chars.add(Map.of("name", name, "value", value)));
            product.put("characteristic", chars);
        }
        Map<String, Object> offering = new LinkedHashMap<>();
        offering.put("id", offeringId);
        if (offeringName != null && !offeringName.isBlank()) {
            offering.put("name", offeringName);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("action", "modify");
        item.put("product", product);
        item.put("productOffering", offering);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("description", description);
        order.put("relatedParty", List.of(Map.of("id", partyId, "role", "customer")));
        order.put("productOrderItem", List.of(item));
        return rest.post().uri("/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Content-Type", "application/json")
                .body(order)
                .retrieve()
                .body(Map.class);
    }
}
