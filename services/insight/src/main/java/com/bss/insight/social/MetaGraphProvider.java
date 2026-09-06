package com.bss.insight.social;

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
    @SuppressWarnings("unchecked")
    private Map<String, Object> get(SocialConfig cfg, String token, String path, Map<String, String> query) {
        org.springframework.web.util.UriComponentsBuilder b =
                org.springframework.web.util.UriComponentsBuilder.fromUriString(base(cfg) + path);
        query.forEach((k, v) -> b.queryParam(k,
                java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)));
        java.net.URI uri = b.build(true).toUri(); // values pre-encoded: braces never reach template expansion
        return http.get().uri(uri).header("Authorization", "Bearer " + token).retrieve().body(Map.class);
    }

    private static Map<String, String> q(String fields, int limit) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fields", fields);
        m.put("limit", String.valueOf(limit));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(Object body) {
        return body instanceof Map<?, ?> m && m.get("data") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> from(Map<String, Object> m) {
        return m.get("from") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
    }

    private static Map<String, Object> row(String id, String platform, String author, String handle, String text,
            String created, String permalink) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("platform", platform);
        o.put("author", author);
        o.put("handle", handle);
        o.put("text", text == null ? "" : text);
        o.put("created_time", created);
        if (permalink != null) {
            o.put("permalink", permalink);
        }
        return o;
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
    public List<Map<String, Object>> mentions(SocialConfig cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        String page = cfg.accountId();
        fenced("tagged posts", () -> {
            for (Map<String, Object> p : data(get(cfg, cfg.accessToken(), "/" + page + "/tagged",
                    q("id,message,from{name,id},created_time,permalink_url", 50)))) {
                Map<String, Object> f = from(p);
                out.add(row(str(p.get("id")), "facebook", str(f.get("name")), str(f.get("id")),
                        str(p.get("message")), str(p.get("created_time")), str(p.get("permalink_url"))));
            }
        });
        fenced("post comments", () -> {
            for (Map<String, Object> post : data(get(cfg, cfg.accessToken(), "/" + page + "/feed",
                    q("id,comments.limit(25){id,message,from{name,id},created_time}", 25)))) {
                for (Map<String, Object> c : data(post.get("comments"))) {
                    Map<String, Object> f = from(c);
                    if (page.equals(str(f.get("id")))) {
                        continue; // the brand's own replies are not mentions
                    }
                    out.add(row(str(c.get("id")), "facebook", str(f.get("name")), str(f.get("id")),
                            str(c.get("message")), str(c.get("created_time")), null));
                }
            }
        });
        if (cfg.igUserId() != null && !cfg.igUserId().isBlank()) {
            fenced("instagram tags", () -> {
                for (Map<String, Object> t : data(get(cfg, cfg.accessToken(), "/" + cfg.igUserId() + "/tags",
                        q("id,caption,username,timestamp,permalink", 50)))) {
                    String user = str(t.get("username"));
                    out.add(row(str(t.get("id")), "instagram", user, user == null ? null : "@" + user,
                            str(t.get("caption")), str(t.get("timestamp")), str(t.get("permalink"))));
                }
            });
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> dms(SocialConfig cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        String page = cfg.accountId();
        for (String platform : cfg.igUserId() != null && !cfg.igUserId().isBlank()
                ? List.of("messenger", "instagram") : List.of("messenger")) {
            fenced(platform + " conversations", () -> {
                Map<String, String> query = q("id,updated_time,messages.limit(10){id,message,from{name,id,username},created_time}", 25);
                query.put("platform", platform);
                for (Map<String, Object> conv : data(get(cfg, cfg.accessToken(), "/" + page + "/conversations", query))) {
                    for (Map<String, Object> m : data(conv.get("messages"))) {
                        Map<String, Object> f = from(m);
                        if (page.equals(str(f.get("id")))) {
                            continue; // our own replies are not inbound
                        }
                        String user = str(f.get("username"));
                        out.add(row(str(m.get("id")), platform,
                                str(f.get("name")) != null ? str(f.get("name")) : user,
                                user != null ? "@" + user : str(f.get("id")),
                                str(m.get("message")), str(m.get("created_time")), null));
                    }
                }
            });
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(SocialConfig cfg, String message) {
        Map<String, Object> res = http.post().uri(java.net.URI.create(base(cfg) + "/" + cfg.accountId() + "/feed"))
                .header("Authorization", "Bearer " + cfg.accessToken())
                .body(Map.of("message", message)).retrieve().body(Map.class);
        String id = res == null ? "" : String.valueOf(res.get("id"));
        return Map.of("id", id, "permalink", id.isBlank() ? "" : "https://www.facebook.com/" + id);
    }

    @Override
    public List<Map<String, Object>> posts(SocialConfig cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        fenced("posts", () -> {
            for (Map<String, Object> p : data(get(cfg, cfg.accessToken(), "/" + cfg.accountId() + "/posts",
                    q("id,message,created_time,permalink_url", 25)))) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", str(p.get("id")));
                o.put("message", str(p.get("message")));
                o.put("created_time", str(p.get("created_time")));
                o.put("permalink", str(p.get("permalink_url")));
                out.add(o);
            }
        });
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int pushAudience(SocialConfig cfg, String audienceId, List<String> hashedEmails) {
        List<List<String>> rows = hashedEmails.stream().map(List::of).toList();
        Map<String, Object> res = http.post().uri(java.net.URI.create(base(cfg) + "/" + audienceId + "/users"))
                .header("Authorization", "Bearer " + cfg.adsTokenOrPage())
                .body(Map.of("payload", Map.of("schema", List.of("EMAIL"), "data", rows)))
                .retrieve().body(Map.class);
        return res != null && res.get("num_received") instanceof Number n ? n.intValue() : rows.size();
    }

    @Override
    public List<Map<String, Object>> leads(SocialConfig cfg, String formId) {
        List<Map<String, Object>> out = new ArrayList<>();
        fenced("leads", () -> out.addAll(data(get(cfg, cfg.accessToken(), "/" + formId + "/leads",
                q("id,created_time,field_data", 100)))));
        return out;
    }
}
