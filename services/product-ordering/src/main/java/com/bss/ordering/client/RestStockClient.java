package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "bss.stock.enabled", havingValue = "true", matchIfMissing = true)
public class RestStockClient implements StockClient {

    private static final String BASE = "/tmf-api/productStockManagement/v4";

    private final RestClient restClient;

    public RestStockClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.stock-base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public ReserveOutcome reserve(String productOfferingId, String offeringName, int quantity, String orderId) {
        try {
            restClient.post()
                    .uri(BASE + "/reserveProductStock")
                    .body(Map.of(
                            "productOffering", Map.of("id", productOfferingId, "name", offeringName),
                            "quantity", quantity,
                            "relatedOrder", Map.of("id", orderId)))
                    .retrieve()
                    .toBodilessEntity();
            return ReserveOutcome.reserved();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                return ReserveOutcome.insufficient(reasonIn(e, offeringName));
            }
            throw new DownstreamException("product-stock rejected the reservation", e);
        } catch (RestClientException e) {
            throw new DownstreamException("product-stock is unreachable", e);
        }
    }

    @Override
    public void release(String orderId) {
        post("/releaseProductStock", orderId);
    }

    @Override
    public void consume(String orderId) {
        post("/consumeProductStock", orderId);
    }

    private void post(String path, String orderId) {
        try {
            restClient.post()
                    .uri(BASE + path)
                    .body(Map.of("relatedOrder", Map.of("id", orderId)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("product-stock rejected or is unreachable", e);
        }
    }

    private String reasonIn(HttpClientErrorException e, String offeringName) {
        try {
            String message = String.valueOf(e.getResponseBodyAs(Map.class).get("message"));
            return message == null || "null".equals(message) ? "insufficient stock for '" + offeringName + "'" : message;
        } catch (Exception ignored) {
            return "insufficient stock for '" + offeringName + "'";
        }
    }
}
