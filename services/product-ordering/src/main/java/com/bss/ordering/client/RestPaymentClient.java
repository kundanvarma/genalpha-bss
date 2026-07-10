package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "bss.payment.enabled", havingValue = "true", matchIfMissing = true)
public class RestPaymentClient implements PaymentClient {

    private static final String BASE = "/tmf-api/paymentManagement/v4/payment";

    private final RestClient restClient;

    public RestPaymentClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.payment-base-url}") String baseUrl) {
        // The default HttpURLConnection factory cannot send PATCH, which this
        // client needs for capture/void — the JDK HttpClient factory can.
        this.restClient = builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .requestInterceptor(tokenInterceptor)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String validateAuthorized(String paymentId, String expectedOwnerPartyId, String orderId) {
        Map<String, Object> payment;
        try {
            payment = restClient.get().uri(BASE + "/" + paymentId).retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            return "payment '" + paymentId + "' not found";
        } catch (RestClientException e) {
            throw new DownstreamException("payment service is unreachable", e);
        }
        if (!"authorized".equals(payment.get("status"))) {
            return "payment '" + paymentId + "' is '" + payment.get("status") + "', not authorized";
        }
        Object payer = ((List<Map<String, Object>>) payment.getOrDefault("relatedParty", List.of()))
                .stream().map(p -> p.get("id")).findFirst().orElse(null);
        if (expectedOwnerPartyId != null && payer != null && !expectedOwnerPartyId.equals(payer)) {
            return "payment '" + paymentId + "' belongs to another party";
        }
        // Tie the payment to this order for the audit trail.
        try {
            restClient.patch().uri(BASE + "/" + paymentId)
                    .header("Content-Type", "application/json")
                    .body(Map.of("correlatorId", orderId))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("payment service rejected the correlator update", e);
        }
        return "";
    }

    @Override
    public void capture(String paymentId) {
        transition(paymentId, "captured");
    }

    @Override
    public void voidPayment(String paymentId) {
        transition(paymentId, "voided");
    }

    private void transition(String paymentId, String status) {
        try {
            restClient.patch().uri(BASE + "/" + paymentId)
                    .header("Content-Type", "application/json")
                    .body(Map.of("status", status))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("payment service rejected '" + status + "'", e);
        }
    }
}
