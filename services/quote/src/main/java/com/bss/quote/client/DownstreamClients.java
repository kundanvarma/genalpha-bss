package com.bss.quote.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * What quoting needs to see: the intent's proposal (OSS), the catalog's
 * prices, the usage component's token allowances — and one write: the
 * accepted quote becomes a product order. All calls run under the acting
 * tenant's machine identity.
 */
@Component
public class DownstreamClients {

    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final RestClient som;
    private final RestClient catalog;
    private final RestClient usage;
    private final RestClient ordering;
    private final RestClient intelligence;
    private final ObjectMapper objectMapper;

    public DownstreamClients(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.downstream.som-base-url:http://localhost:8104}") String somBaseUrl,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String catalogBaseUrl,
            @Value("${bss.downstream.usage-base-url:http://localhost:8097}") String usageBaseUrl,
            @Value("${bss.downstream.ordering-base-url:http://localhost:8082}") String orderingBaseUrl,
            @Value("${bss.downstream.intelligence-base-url:http://localhost:8109}") String intelligenceBaseUrl) {
        this.som = builder.baseUrl(somBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.catalog = builder.baseUrl(catalogBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.usage = builder.baseUrl(usageBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.ordering = builder.baseUrl(orderingBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.intelligence = builder.baseUrl(intelligenceBaseUrl).requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> intent(String intentId) {
        return parseObject(som.get().uri("/tmf-api/intentManagement/v4/intent/" + intentId)
                .retrieve().body(String.class));
    }

    public List<Map<String, Object>> offerings() {
        return parseList(catalog.get()
                .uri("/tmf-api/productCatalogManagement/v4/productOffering?limit=100")
                .retrieve().body(String.class));
    }

    public Map<String, Object> offeringPrice(String priceId) {
        return parseObject(catalog.get()
                .uri("/tmf-api/productCatalogManagement/v4/productOfferingPrice/" + priceId)
                .retrieve().body(String.class));
    }

    public List<Map<String, Object>> allowances() {
        return parseList(usage.get()
                .uri("/tmf-api/usageManagement/v4/usageAllowance?limit=100")
                .retrieve().body(String.class));
    }

    public Map<String, Object> placeOrder(Map<String, Object> order) {
        return parseObject(ordering.post()
                .uri("/tmf-api/productOrderingManagement/v4/productOrder")
                .header("Content-Type", "application/json")
                .body(order).retrieve().body(String.class));
    }

    /** Fail-soft: a quote without prose is still a quote. */
    public String quoteNarrative(Map<String, Object> context) {
        try {
            Map<String, Object> reply = parseObject(intelligence.post()
                    .uri("/ai/v1/quoteNarrative")
                    .header("Content-Type", "application/json")
                    .body(context).retrieve().body(String.class));
            return reply.get("narrative") == null ? null : String.valueOf(reply.get("narrative"));
        } catch (RestClientException e) {
            return null;
        }
    }

    private Map<String, Object> parseObject(String body) {
        try {
            return objectMapper.readValue(body, OBJECT);
        } catch (Exception e) {
            throw new IllegalStateException("downstream answer unreadable", e);
        }
    }

    private List<Map<String, Object>> parseList(String body) {
        try {
            return objectMapper.readValue(body, LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
