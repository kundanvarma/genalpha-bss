package com.bss.insight.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Push an audience OUT to an ad/social platform as a Custom Audience — the
 * acquisition seam. This is how a first-party segment reaches STRANGERS: the
 * platform builds a lookalike from a seed, or excludes a suppression list from
 * paid spend. PII never leaves in clear — emails are SHA-256 hashed here, and
 * only the hashes cross the wire (Meta/Google Customer Match contract). Pushed
 * in batches so a million-row audience is many small posts, not one giant one.
 */
@Component
public class SocialAudienceClient {

    private static final Logger log = LoggerFactory.getLogger(SocialAudienceClient.class);
    private static final int BATCH = 500;

    private final RestClient http;
    private final String baseUrl;
    private final String token;

    public SocialAudienceClient(RestClient.Builder builder,
            @Value("${bss.downstream.social-api-url:}") String baseUrl,
            @Value("${bss.downstream.social-access-token:}") String token) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /** @return number of hashed identifiers accepted by the platform. */
    public int pushCustomAudience(String externalAudienceId, List<String> emails) {
        if (!enabled() || emails.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (int i = 0; i < emails.size(); i += BATCH) {
            List<List<String>> data = new ArrayList<>();
            for (String email : emails.subList(i, Math.min(i + BATCH, emails.size()))) {
                data.add(List.of(sha256(email.trim().toLowerCase())));
            }
            try {
                http.post().uri(baseUrl + "/v1/{aid}/users", externalAudienceId)
                        .header("Authorization", "Bearer " + token)
                        .body(Map.of("schema", List.of("EMAIL_SHA256"), "data", data))
                        .retrieve().toBodilessEntity();
                sent += data.size();
            } catch (Exception e) {
                log.warn("custom-audience push batch failed ({} rows): {}", data.size(), e.getMessage());
            }
        }
        return sent;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
