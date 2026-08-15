package com.bss.communication.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * One-click unsubscribe tokens: an HMAC of the party id, so a public unsubscribe
 * link can't be forged for another customer (no login needed to honour it, per
 * RFC 8058), yet only the operator's secret can mint one. Deterministic, so the
 * footer that builds it and the endpoint that verifies it agree without storage.
 */
@Component
public class UnsubscribeToken {

    private final byte[] secret;
    private final String baseUrl;

    public UnsubscribeToken(
            @Value("${bss.communication.unsubscribe-secret:genalpha-dev-unsub-secret}") String secret,
            @Value("${bss.communication.public-base-url:http://localhost:8080}") String baseUrl) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.baseUrl = baseUrl;
    }

    public String forParty(String partyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(partyId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(sig.length * 2);
            for (byte b : sig) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 24); // 96 bits — plenty for a link token
        } catch (Exception e) {
            throw new IllegalStateException("could not mint unsubscribe token", e);
        }
    }

    public boolean valid(String partyId, String token) {
        return partyId != null && token != null && constantTimeEquals(forParty(partyId), token);
    }

    /** A one-click unsubscribe URL to drop in a marketing message footer. */
    public String linkFor(String partyId) {
        return baseUrl + "/esp/v1/unsubscribe?p=" + partyId + "&t=" + forParty(partyId);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
