package com.bss.insight.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Google Customer Match: POST /google/v1/{listId}/members
 * {operations:[{create:{hashed_email}}]} — a different wire shape, same hashes. */
@Component
public class GoogleDestination implements AdDestination {

    private static final Logger log = LoggerFactory.getLogger(GoogleDestination.class);
    private static final int BATCH = 500;

    private final RestClient http;
    private final String baseUrl;
    private final String token;

    public GoogleDestination(RestClient.Builder builder,
            @Value("${bss.downstream.google-api-url:}") String baseUrl,
            @Value("${bss.downstream.google-access-token:}") String token) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public String name() { return "google"; }

    public boolean enabled() { return baseUrl != null && !baseUrl.isBlank(); }

    public int push(String externalAudienceId, List<String> hashedEmails) {
        if (!enabled() || hashedEmails.isEmpty()) return 0;
        int sent = 0;
        for (int i = 0; i < hashedEmails.size(); i += BATCH) {
            List<Map<String, Object>> ops = new ArrayList<>();
            for (String h : hashedEmails.subList(i, Math.min(i + BATCH, hashedEmails.size()))) {
                ops.add(Map.of("create", Map.of("hashed_email", h)));
            }
            try {
                http.post().uri(baseUrl + "/google/v1/{lid}/members", externalAudienceId)
                        .header("Authorization", "Bearer " + token)
                        .body(Map.of("operations", ops))
                        .retrieve().toBodilessEntity();
                sent += ops.size();
            } catch (Exception e) {
                log.warn("google push batch failed ({} rows): {}", ops.size(), e.getMessage());
            }
        }
        return sent;
    }
}
