package com.bss.insight.social;

import com.bss.insight.dto.PublishedPost;
import com.bss.insight.dto.SocialMessage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
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

    private static List<JsonNode> data(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body != null && body.path("data").isArray()) {
            body.path("data").forEach(out::add);
        }
        return out;
    }

    /** The mock's row is already the normalised shape — read it field by field. */
    static SocialMessage message(JsonNode m) {
        return new SocialMessage(text(m, "id"), text(m, "platform"), text(m, "author"), text(m, "handle"), text(m, "text"),
                text(m, "created_time"), text(m, "permalink"));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private JsonNode get(SocialConfig cfg, String path, String var, String token) {
        return http.get().uri(base(cfg) + path, var).header("Authorization", "Bearer " + token)
                .retrieve().body(JsonNode.class);
    }

    @Override
    public List<SocialMessage> mentions(SocialConfig cfg) {
        return data(get(cfg, "/v1/{acct}/mentions", cfg.accountId(), cfg.accessToken()))
                .stream().map(MockSocialProvider::message).toList();
    }

    @Override
    public List<SocialMessage> dms(SocialConfig cfg) {
        return data(get(cfg, "/v1/{acct}/dms", cfg.accountId(), cfg.accessToken()))
                .stream().map(MockSocialProvider::message).toList();
    }

    @Override
    public PublishedPost publish(SocialConfig cfg, String message) {
        JsonNode res = http.post().uri(base(cfg) + "/v1/{acct}/posts", cfg.accountId())
                .header("Authorization", "Bearer " + cfg.accessToken())
                .body(Map.of("message", message)).retrieve().body(JsonNode.class);
        return new PublishedPost(res == null ? "" : res.path("id").asText(),
                res == null ? "" : res.path("permalink").asText());
    }

    @Override
    public List<JsonNode> posts(SocialConfig cfg) {
        return data(get(cfg, "/v1/{acct}/posts", cfg.accountId(), cfg.accessToken()));
    }

    @Override
    public int pushAudience(SocialConfig cfg, String audienceId, List<String> hashedEmails) {
        List<List<String>> rows = hashedEmails.stream().map(List::of).toList();
        JsonNode res = http.post().uri(base(cfg) + "/v1/{aid}/users", audienceId)
                .header("Authorization", "Bearer " + cfg.adsTokenOrPage())
                .body(Map.of("schema", List.of("EMAIL_SHA256"), "data", rows)).retrieve().body(JsonNode.class);
        return res != null && res.path("num_received").isNumber() ? res.path("num_received").intValue() : rows.size();
    }

    @Override
    public List<JsonNode> leads(SocialConfig cfg, String formId) {
        return data(get(cfg, "/v1/{form}/leads", formId, cfg.accessToken()));
    }
}
