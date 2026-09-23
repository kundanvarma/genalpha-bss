package com.bss.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Where to send the customer for a redirect/BNPL session, and who actually served it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"sessionId", "redirectUrl", "provider", "failedOverFrom", "@type"})
public record PaymentSession(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("redirectUrl") String redirectUrl,
        @JsonProperty("provider") String provider,
        @JsonProperty("failedOverFrom") String failedOverFrom,
        @JsonProperty("@type") String type) {

    public static PaymentSession served(String sessionId, String redirectUrl, String provider) {
        return new PaymentSession(sessionId, redirectUrl, provider, null, "PaymentSession");
    }

    /** The same session, naming the provider the failover replaced. */
    public PaymentSession failedOverFrom(String primary) {
        return new PaymentSession(sessionId, redirectUrl, provider, primary, type);
    }
}
