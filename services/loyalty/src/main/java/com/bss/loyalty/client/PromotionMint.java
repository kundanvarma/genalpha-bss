package com.bss.loyalty.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Mints a loyalty voucher as a REAL promotion (TMF671): a unique code per
 * redemption — the promotion component's once-per-customer redemption plus
 * a code nobody else knows makes it effectively single-use. Machine call
 * under loyalty's OWN identity (bss-loyalty, promotion:write).
 */
@Component
public class PromotionMint {

    private final RestClient rest;

    public PromotionMint(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.promotion-base-url:http://localhost:8099}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** Create the voucher promotion; returns the minted code. */
    public String mint(String code, int percent, String forPartyNote) {
        rest.post().uri("/tmf-api/promotionManagement/v4/promotion")
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "name", "Loyalty voucher " + code,
                        "description", "Redeemed with points" + (forPartyNote == null ? "" : " " + forPartyNote),
                        "code", code,
                        "percentage", percent,
                        "durationMonths", 1,
                        "validFor", Map.of(
                                "startDateTime", java.time.OffsetDateTime.now().toString(),
                                "endDateTime", java.time.OffsetDateTime.now().plusDays(90).toString())))
                .retrieve().toBodilessEntity();
        return code;
    }
}
