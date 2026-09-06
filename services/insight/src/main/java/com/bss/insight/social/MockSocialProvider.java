package com.bss.insight.social;

import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** The normalised dev shape served by mock-social: /v1/{account}/mentions|dms|posts, /v1/{id}/users, /v1/{form}/leads. */
public class MockSocialProvider implements SocialProvider {

    private final RestClient http;

    public MockSocialProvider(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public String name() {
        return "mock";
    }

    private String base(SocialConfig cfg) {
        return cfg.apiUrl().replaceAll("/+$", "");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> data(Map<String, Object> body) {
        return body != null && body.get("data") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> mentions(SocialConfig cfg) {
        return data(http.get().uri(base(cfg) + "/v1/{acct}/mentions", cfg.accountId())
                .header("Authorization", "Bearer " + cfg.accessToken()).retrieve().body(Map.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> dms(SocialConfig cfg) {
        return data(http.get().uri(base(cfg) + "/v1/{acct}/dms", cfg.accountId())
                .header("Authorization", "Bearer " + cfg.accessToken()).retrieve().body(Map.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(SocialConfig cfg, String message) {
        Map<String, Object> res = http.post().uri(base(cfg) + "/v1/{acct}/posts", cfg.accountId())
                .header("Authorization", "Bearer " + cfg.accessToken())
                .body(Map.of("message", message)).retrieve().body(Map.class);
        return Map.of("id", res == null ? "" : String.valueOf(res.get("id")),
                "permalink", res == null ? "" : String.valueOf(res.get("permalink")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> posts(SocialConfig cfg) {
        return data(http.get().uri(base(cfg) + "/v1/{acct}/posts", cfg.accountId())
                .header("Authorization", "Bearer " + cfg.accessToken()).retrieve().body(Map.class));
    }

    @Override
    @SuppressWarnings("unchecked")
    public int pushAudience(SocialConfig cfg, String audienceId, List<String> hashedEmails) {
        List<List<String>> rows = hashedEmails.stream().map(List::of).toList();
        Map<String, Object> res = http.post().uri(base(cfg) + "/v1/{aid}/users", audienceId)
                .header("Authorization", "Bearer " + cfg.adsTokenOrPage())
                .body(Map.of("schema", List.of("EMAIL_SHA256"), "data", rows)).retrieve().body(Map.class);
        return res != null && res.get("num_received") instanceof Number n ? n.intValue() : rows.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> leads(SocialConfig cfg, String formId) {
        return data(http.get().uri(base(cfg) + "/v1/{form}/leads", formId)
                .header("Authorization", "Bearer " + cfg.accessToken()).retrieve().body(Map.class));
    }
}
