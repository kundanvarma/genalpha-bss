package com.bss.cart.client;

import com.bss.cart.dto.AcpFeedItem;
import com.bss.cart.dto.PaymentRequest;
import com.bss.cart.dto.ProductOrderRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * The ACP adapter's three downstream calls. Pricing is anonymous (the feed
 * is public); ordering and payment FORWARD THE CALLER'S OWN TOKEN — the
 * delegated, commerce-scoped credential the agent presented. The cart
 * service never lends its own authority to a purchase: an agent that
 * arrives with too little authority is refused by the same services that
 * refuse any under-privileged human channel.
 *
 * What this service SENDS is typed ({@link PaymentRequest},
 * {@link ProductOrderRequest}); what comes BACK from another component is
 * its document, a tree — the caller reads the one key it needs.
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
    public AcpFeedItem feedItem(String offeringId, String tenantId) {
        try {
            AcpFeedItem.Feed feed = catalog.get()
                    .uri("/acp/product_feed?id={id}", offeringId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve().body(AcpFeedItem.Feed.class);
            List<AcpFeedItem> products = feed == null || feed.products() == null ? List.of() : feed.products();
            return products.isEmpty() ? null : products.get(0);
        } catch (RestClientResponseException e) {
            return null;
        }
    }

    /**
     * TMF760 check: the configurator is the single authority on whether a
     * pick set is orderable and what it costs. Anonymous like the feed —
     * configuring is browsing — with the tenant carried the same way. The
     * verdict is the configurator's document, answered as a tree.
     */
    public JsonNode checkConfiguration(JsonNode productConfiguration, String tenantId) {
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.set("productConfiguration", productConfiguration);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putArray("checkProductConfigurationItem").add(item);
        return catalog.post()
                .uri("/tmf-api/productConfigurationManagement/v5/checkProductConfiguration")
                .header("X-Tenant-Id", tenantId)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(JsonNode.class);
    }

    /** POST the payment AS THE CALLER — their delegated token, their charge. */
    public JsonNode createPayment(PaymentRequest body, String authorization) {
        return payment.post().uri("/tmf-api/paymentManagement/v4/payment")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(JsonNode.class);
    }

    /** POST the order AS THE CALLER — the delegated token decides what is allowed. */
    public JsonNode createOrder(ProductOrderRequest body, String authorization) {
        return ordering.post().uri("/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve().body(JsonNode.class);
    }
}
