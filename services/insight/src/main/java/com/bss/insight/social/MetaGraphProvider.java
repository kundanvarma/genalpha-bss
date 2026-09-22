package com.bss.insight.social;

import com.bss.insight.dto.PublishedPost;
import com.bss.insight.dto.SocialMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Meta Graph API adapter (Facebook Page + Instagram professional account +
 * Marketing API), read with a Page access token and written with the ads token.
 * <p>
 * What it reads, and the permission each needs (Development-mode apps grant
 * these to anyone with a role on the app; App Review only for third-party pages):
 * <ul>
 *   <li>mentions: {@code GET /{page}/tagged} + comments on {@code /{page}/feed}
 *       (pages_read_engagement, pages_read_user_content) and, with an IG id,
 *       {@code GET /{ig}/tags} (instagram_basic, instagram_manage_comments)</li>
 *   <li>dms: {@code GET /{page}/conversations?platform=messenger|instagram}
 *       (pages_messaging, instagram_manage_messages)</li>
 *   <li>publish: {@code POST /{page}/feed} (pages_manage_posts)</li>
 *   <li>audiences: {@code POST /{audience}/users {payload:{schema:[EMAIL],data}}} (ads_management)</li>
 *   <li>leads: {@code GET /{form}/leads} (leads_retrieval)</li>
 * </ul>
 * A missing permission fails ONE feed, never the sync: each read is fenced so a
 * page without Instagram still lists Facebook mentions. First page only (50 rows):
 * syncs are idempotent per external id and run every few minutes, so a cursor
 * would only matter for a brand with more than 50 new mentions between two ticks.
 * The Graph documents are foreign: read as trees, never re-shaped.
 */
public class MetaGraphProvider implements SocialProvider {

    private static final Logger log = LoggerFactory.getLogger(MetaGraphProvider.class);
    private final RestClient http;

    public MetaGraphProvider(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override
    public String name() {
        return "meta";
    }

    private String base(SocialConfig cfg) {
        return cfg.apiUrl().replaceAll("/+$", "") + "/" + cfg.versionOrDefault();
    }

    /**
     * Graph field selectors carry braces ({@code comments{id,message}}), which a
     * URI template would try to expand — so the query is built and encoded
     * explicitly, never passed through template expansion.
     */
    private JsonNode get(SocialConfig cfg, String token, String path, Map<String, String> query) {
        org.springframework.web.util.UriComponentsBuilder b =
                org.springframework.web.util.UriComponentsBuilder.fromUriString(base(cfg) + path);
        query.forEach((k, v) -> b.queryParam(k,
                java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)));
        java.net.URI uri = b.build(true).toUri(); // values pre-encoded: braces never reach template expansion
        return http.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
    }

    private static Map<String, String> q(String fields, int limit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fields", fields);
        m.put("limit", String.valueOf(limit));
        return m;
    }

    private static List<JsonNode> data(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body != null && body.path("data").isArray()) {
            body.path("data").forEach(out::add);
        }
        return out;
    }

    private static String str(JsonNode node, String key) {
        JsonNode v = node == null ? null : node.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static JsonNode from(JsonNode m) {
        return m.path("from");
    }

    private static SocialMessage row(String id, String platform, String author, String handle, String text,
            String created, String permalink) {
        return new SocialMessage(id, platform, author, handle, text == null ? "" : text, created, permalink);
    }

    private interface Feed {
        void read() throws Exception;
    }

    private static void fenced(String what, Feed feed) {
        try {
            feed.read();
        } catch (Exception e) {
            log.warn("meta {}: {}", what, e.getMessage());
        }
    }

    @Override
    public List<SocialMessage> mentions(SocialConfig cfg) {
        List<SocialMessage> out = new ArrayList<>();
        String page = cfg.accountId();
        fenced("tagged posts", () -> {
            for (JsonNode p : data(get(cfg, cfg.accessToken(), "/" + page + "/tagged",
                    q("id,message,from{name,id},created_time,permalink_url", 50)))) {
                JsonNode f = from(p);
                out.add(row(str(p, "id"), "facebook", str(f, "name"), str(f, "id"),
                        str(p, "message"), str(p, "created_time"), str(p, "permalink_url")));
            }
        });
        fenced("post comments", () -> {
            for (JsonNode post : data(get(cfg, cfg.accessToken(), "/" + page + "/feed",
                    q("id,comments.limit(25){id,message,from{name,id},created_time}", 25)))) {
                for (JsonNode c : data(post.get("comments"))) {
                    JsonNode f = from(c);
                    if (page.equals(str(f, "id"))) {
                        continue; // the brand's own replies are not mentions
                    }
                    out.add(row(str(c, "id"), "facebook", str(f, "name"), str(f, "id"),
                            str(c, "message"), str(c, "created_time"), null));
                }
            }
        });
        if (cfg.igUserId() != null && !cfg.igUserId().isBlank()) {
            fenced("instagram tags", () -> {
                for (JsonNode t : data(get(cfg, cfg.accessToken(), "/" + cfg.igUserId() + "/tags",
                        q("id,caption,username,timestamp,permalink", 50)))) {
                    String user = str(t, "username");
                    out.add(row(str(t, "id"), "instagram", user, user == null ? null : "@" + user,
                            str(t, "caption"), str(t, "timestamp"), str(t, "permalink")));
                }
            });
        }
        return out;
    }

    @Override
    public List<SocialMessage> dms(SocialConfig cfg) {
        List<SocialMessage> out = new ArrayList<>();
        String page = cfg.accountId();
        for (String platform : cfg.igUserId() != null && !cfg.igUserId().isBlank()
                ? List.of("messenger", "instagram") : List.of("messenger")) {
            fenced(platform + " conversations", () -> {
                Map<String, String> query = q("id,updated_time,messages.limit(10){id,message,from{name,id,username},created_time}", 25);
                query.put("platform", platform);
                for (JsonNode conv : data(get(cfg, cfg.accessToken(), "/" + page + "/conversations", query))) {
                    for (JsonNode m : data(conv.get("messages"))) {
                        JsonNode f = from(m);
                        if (page.equals(str(f, "id"))) {
                            continue; // our own replies are not inbound
                        }
                        String user = str(f, "username");
                        out.add(row(str(m, "id"), platform,
                                str(f, "name") != null ? str(f, "name") : user,
                                user != null ? "@" + user : str(f, "id"),
                                str(m, "message"), str(m, "created_time"), null));
                    }
                }
            });
        }
        return out;
    }

    @Override
    public PublishedPost publish(SocialConfig cfg, String message) {
        JsonNode res = http.post().uri(java.net.URI.create(base(cfg) + "/" + cfg.accountId() + "/feed"))
                .header("Authorization", "Bearer " + cfg.accessToken())
                .body(Map.of("message", message)).retrieve().body(JsonNode.class);
        String id = res == null ? "" : res.path("id").asText();
        return new PublishedPost(id, id.isBlank() ? "" : "https://www.facebook.com/" + id);
    }

    @Override
    public List<JsonNode> posts(SocialConfig cfg) {
        List<JsonNode> out = new ArrayList<>();
        fenced("posts", () -> {
            for (JsonNode p : data(get(cfg, cfg.accessToken(), "/" + cfg.accountId() + "/posts",
                    q("id,message,created_time,permalink_url", 25)))) {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                o.put("id", str(p, "id"));
                o.put("message", str(p, "message"));
                o.put("created_time", str(p, "created_time"));
                o.put("permalink", str(p, "permalink_url"));
                out.add(o);
            }
        });
        return out;
    }

    @Override
    public int pushAudience(SocialConfig cfg, String audienceId, List<String> hashedEmails) {
        List<List<String>> rows = hashedEmails.stream().map(List::of).toList();
        JsonNode res = http.post().uri(java.net.URI.create(base(cfg) + "/" + audienceId + "/users"))
                .header("Authorization", "Bearer " + cfg.adsTokenOrPage())
                .body(Map.of("payload", Map.of("schema", List.of("EMAIL"), "data", rows)))
                .retrieve().body(JsonNode.class);
        return res != null && res.path("num_received").isNumber() ? res.path("num_received").intValue() : rows.size();
    }

    @Override
    public List<JsonNode> leads(SocialConfig cfg, String formId) {
        List<JsonNode> out = new ArrayList<>();
        fenced("leads", () -> out.addAll(data(get(cfg, cfg.accessToken(), "/" + formId + "/leads",
                q("id,created_time,field_data", 100)))));
        return out;
    }
}
