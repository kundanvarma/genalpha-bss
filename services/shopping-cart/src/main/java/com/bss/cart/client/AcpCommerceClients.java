package com.bss.cart.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * The ACP adapter's three downstream calls. Pricing is anonymous (the feed
 * is public); ordering and payment FORWARD THE CALLER'S OWN TOKEN — the
 * delegated, commerce-scoped credential the agent presented. The cart
 * service never lends its own authority to a purchase: an agent that
 * arrives with too little authority is refused by the same services that
 * refuse any under-privileged human channel.
 */
@Component
public class AcpCommerceClients {

    private final RestClient catalog;
    private final RestClient ordering;
    private final RestClient payment;

    public AcpCommerceClients(RestClient.Builder builder,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String catalogUrl,
            @Value("${bss.downstream.ordering-base-url:http://localhost:8082}") String orderingUrl,
            @Value("${bss.downstream.payment-base-url:http://localhost:8087}") String paymentUrl) {
        this.catalog = builder.clone().baseUrl(catalogUrl).build();
        this.ordering = builder.clone().baseUrl(orderingUrl).build();
        this.payment = builder.clone().baseUrl(paymentUrl).build();
    }

    /** One priced feed row for this offering, or null when the catalog
     * cannot price it (unknown id, no unconditioned price). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> feedItem(String offeringId, String tenantId) {
        try {
            Map<String, Object> feed = catalog.get()
                    .uri("/acp/product_feed?id={id}", offeringId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve().body(Map.class);
            List<Map<String, Object>> products = feed == null
                    ? List.of() : (List<Map<String, Object>>) feed.getOrDefault("products", List.of());
            return products.isEmpty() ? null : products.get(0);
        } catch (RestClientResponseException e) {
            return null;
        }
    }

    /** POST the payment AS THE CALLER — their delegated token, their charge. */
    public Map<String, Object> createPayment(Map<String, Object> body, String authorization) {
        return payment.post().uri("/tmf-api/paymentManagement/v4/payment")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /** POST the order AS THE CALLER — the delegated token decides what is allowed. */
    public Map<String, Object> createOrder(Map<String, Object> body, String authorization) {
        return ordering.post().uri("/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }
}
