package com.bss.insight.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Meta Custom Audiences: POST /v1/{id}/users {schema:[EMAIL_SHA256], data:[[h]]}. */
@Component
public class MetaDestination implements AdDestination {

    private static final Logger log = LoggerFactory.getLogger(MetaDestination.class);
    private static final int BATCH = 500;

    private final RestClient http;
    private final String baseUrl;
    private final String token;

    public MetaDestination(RestClient.Builder builder,
            @Value("${bss.downstream.social-api-url:}") String baseUrl,
            @Value("${bss.downstream.social-access-token:}") String token) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public String name() { return "meta"; }

    public boolean enabled() { return baseUrl != null && !baseUrl.isBlank(); }

    public int push(String externalAudienceId, List<String> hashedEmails) {
        if (!enabled() || hashedEmails.isEmpty()) return 0;
        int sent = 0;
        for (int i = 0; i < hashedEmails.size(); i += BATCH) {
            List<List<String>> data = new ArrayList<>();
            for (String h : hashedEmails.subList(i, Math.min(i + BATCH, hashedEmails.size()))) data.add(List.of(h));
            try {
                http.post().uri(baseUrl + "/v1/{aid}/users", externalAudienceId)
                        .header("Authorization", "Bearer " + token)
                        .body(Map.of("schema", List.of("EMAIL_SHA256"), "data", data))
                        .retrieve().toBodilessEntity();
                sent += data.size();
            } catch (Exception e) {
                log.warn("meta push batch failed ({} rows): {}", data.size(), e.getMessage());
            }
        }
        return sent;
    }
}
