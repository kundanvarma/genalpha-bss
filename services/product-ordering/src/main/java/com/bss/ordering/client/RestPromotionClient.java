package com.bss.ordering.client;

import com.bss.ordering.exception.DownstreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "bss.promotion.enabled", havingValue = "true", matchIfMissing = true)
public class RestPromotionClient implements PromotionClient {

    private final RestClient restClient;

    public RestPromotionClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.promotion-base-url:http://localhost:8099}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    @Override
    public boolean isValid(String code) {
        try {
            Map<String, Object> result = restClient.post()
                    .uri("/tmf-api/promotionManagement/v4/checkPromotion")
                    .header("Content-Type", "application/json")
                    .body(Map.of("code", code))
                    .retrieve().body(Map.class);
            return result != null && Boolean.TRUE.equals(result.get("valid"));
        } catch (RestClientException e) {
            throw new DownstreamException("promotion service is unreachable", e);
        }
    }

    @Override
    public void redeem(String code, String ownerPartyId) {
        try {
            restClient.post().uri("/tmf-api/promotionManagement/v4/redemption")
                    .header("Content-Type", "application/json")
                    .body(Map.of("code", code, "relatedPartyId", ownerPartyId))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new DownstreamException("promotion service rejected the redemption", e);
        }
    }
}
