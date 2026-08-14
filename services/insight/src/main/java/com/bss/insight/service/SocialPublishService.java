package com.bss.insight.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Organic publishing: put a post OUT on the brand's own handle — the outbound,
 * broadcast side of social (distinct from a per-customer message). Proxies the
 * brand handle's post feed on the platform; the platform is the record.
 */
@Service
public class SocialPublishService {

    private final RestClient social;
    private final String accountId;
    private final String token;
    private final boolean enabled;

    public SocialPublishService(RestClient.Builder builder,
            @Value("${bss.downstream.social-api-url:}") String baseUrl,
            @Value("${bss.downstream.social-account-id:}") String accountId,
            @Value("${bss.downstream.social-access-token:}") String token) {
        this.social = builder.baseUrl(baseUrl == null ? "" : baseUrl).build();
        this.accountId = accountId;
        this.token = token;
        this.enabled = baseUrl != null && !baseUrl.isBlank() && accountId != null && !accountId.isBlank();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(String content) {
        if (!enabled) {
            return Map.of("published", false, "reason", "no social handle configured for this tenant");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required to publish");
        }
        Map<String, Object> res = social.post().uri("/v1/{acct}/posts", accountId)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("message", content))
                .retrieve().body(Map.class);
        return Map.of("published", true, "id", res == null ? "" : String.valueOf(res.get("id")),
                "permalink", res == null ? "" : String.valueOf(res.get("permalink")));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> posts() {
        if (!enabled) {
            return List.of();
        }
        Map<String, Object> body = social.get().uri("/v1/{acct}/posts", accountId)
                .header("Authorization", "Bearer " + token)
                .retrieve().body(Map.class);
        return body != null && body.get("data") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
    }
}
