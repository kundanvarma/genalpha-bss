package com.bss.insight.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The Graph API wire shapes in, the normalised desk shape out — with a stub that speaks Graph. */
class MetaGraphProviderTest {

    static HttpServer server;
    static final Map<String, String> lastBody = new ConcurrentHashMap<>();
    static final Map<String, String> lastAuth = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            lastAuth.put(path, ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.put(path, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body;
            if (path.endsWith("/page1/tagged")) {
                body = "{\"data\":[{\"id\":\"t1\",\"message\":\"love the new fibre\",\"from\":{\"name\":\"Ana\",\"id\":\"u1\"},\"created_time\":\"2026-09-06T10:00:00+0000\",\"permalink_url\":\"https://fb/t1\"}]}";
            } else if (path.endsWith("/page1/feed") && "GET".equals(ex.getRequestMethod())) {
                body = "{\"data\":[{\"id\":\"p1\",\"comments\":{\"data\":[{\"id\":\"c1\",\"message\":\"slow tonight\",\"from\":{\"name\":\"Ben\",\"id\":\"u2\"},\"created_time\":\"2026-09-06T11:00:00+0000\"},{\"id\":\"c2\",\"message\":\"sorry Ben, looking\",\"from\":{\"name\":\"Brand\",\"id\":\"page1\"},\"created_time\":\"2026-09-06T11:05:00+0000\"}]}}]}";
            } else if (path.endsWith("/page1/feed")) {
                body = "{\"id\":\"page1_99\"}";
            } else if (path.endsWith("/ig9/tags")) {
                body = "{\"data\":[{\"id\":\"ig1\",\"caption\":\"great speeds @brand\",\"username\":\"cara\",\"timestamp\":\"2026-09-06T12:00:00+0000\",\"permalink\":\"https://ig/ig1\"}]}";
            } else if (path.endsWith("/page1/conversations")) {
                String platform = q.contains("platform=instagram") ? "instagram" : "messenger";
                body = "{\"data\":[{\"id\":\"conv1\",\"messages\":{\"data\":[{\"id\":\"m1-" + platform + "\",\"message\":\"my bill is wrong\",\"from\":{\"name\":\"Dev\",\"id\":\"u3\"},\"created_time\":\"2026-09-06T13:00:00+0000\"},{\"id\":\"m2\",\"message\":\"we are on it\",\"from\":{\"name\":\"Brand\",\"id\":\"page1\"},\"created_time\":\"2026-09-06T13:01:00+0000\"}]}}]}";
            } else if (path.endsWith("/aud7/users")) {
                body = "{\"audience_id\":\"aud7\",\"num_received\":2,\"num_invalid_entries\":0}";
            } else if (path.endsWith("/form3/leads")) {
                body = "{\"data\":[{\"id\":\"l1\",\"created_time\":\"2026-09-06T14:00:00+0000\",\"field_data\":[{\"name\":\"email\",\"values\":[\"a@b.c\"]}]}]}";
            } else {
                body = "{\"error\":{\"message\":\"(#100) missing permission\",\"code\":100}}";
                ex.getResponseHeaders().add("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(400, b.length);
                ex.getResponseBody().write(b);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private SocialConfig cfg(String ig) {
        return new SocialConfig("meta", "http://localhost:" + server.getAddress().getPort(), "v21.0",
                "page1", ig, "PAGE-TOKEN", "ADS-TOKEN");
    }

    @Test
    void mentions_mergeTaggedPostsCommentsAndInstagram_andDropTheBrandsOwnReplies() {
        List<Map<String, Object>> rows = new MetaGraphProvider(RestClient.builder()).mentions(cfg("ig9"));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("t1", "c1", "ig1");
        assertThat(rows.get(0)).containsEntry("platform", "facebook").containsEntry("author", "Ana")
                .containsEntry("text", "love the new fibre").containsEntry("permalink", "https://fb/t1");
        assertThat(rows.get(2)).containsEntry("platform", "instagram").containsEntry("handle", "@cara");
        assertThat(lastAuth.get("/v21.0/page1/tagged")).isEqualTo("Bearer PAGE-TOKEN");
    }

    @Test
    void mentions_withoutInstagram_skipTheTagsFeed_andAMissingPermissionFailsOnlyThatFeed() {
        SocialConfig noIg = new SocialConfig("meta", "http://localhost:" + server.getAddress().getPort(), null,
                "page1", null, "PAGE-TOKEN", null);
        List<Map<String, Object>> rows = new MetaGraphProvider(RestClient.builder()).mentions(noIg);
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("t1", "c1");
        // a page id the stub answers with a Graph error: the sync survives with zero rows
        SocialConfig broken = new SocialConfig("meta", "http://localhost:" + server.getAddress().getPort(), null,
                "nopage", null, "PAGE-TOKEN", null);
        assertThat(new MetaGraphProvider(RestClient.builder()).mentions(broken)).isEmpty();
    }

    @Test
    void dms_readInboundMessagesPerPlatform_andDropOurOwnReplies() {
        List<Map<String, Object>> rows = new MetaGraphProvider(RestClient.builder()).dms(cfg("ig9"));
        assertThat(rows).extracting(r -> r.get("id")).containsExactly("m1-messenger", "m1-instagram");
        assertThat(rows.get(0)).containsEntry("platform", "messenger").containsEntry("author", "Dev")
                .containsEntry("text", "my bill is wrong");
        assertThat(rows.get(1)).containsEntry("platform", "instagram");
    }

    @Test
    void publish_postsToTheFeed_andReturnsAPermalink() {
        Map<String, Object> res = new MetaGraphProvider(RestClient.builder()).publish(cfg(null), "Match day boost is on");
        assertThat(res).containsEntry("id", "page1_99").containsEntry("permalink", "https://www.facebook.com/page1_99");
        assertThat(lastBody.get("/v21.0/page1/feed")).contains("Match day boost is on");
    }

    @Test
    void audiences_useTheGraphPayloadShape_andTheAdsToken() {
        int n = new MetaGraphProvider(RestClient.builder()).pushAudience(cfg(null), "aud7", List.of("h1", "h2"));
        assertThat(n).isEqualTo(2);
        assertThat(lastBody.get("/v21.0/aud7/users")).contains("\"payload\"").contains("\"schema\":[\"EMAIL\"]").contains("[[\"h1\"],[\"h2\"]]");
        assertThat(lastAuth.get("/v21.0/aud7/users")).isEqualTo("Bearer ADS-TOKEN");
    }

    @Test
    void leads_comeBackInMetaFieldDataShape() {
        List<Map<String, Object>> rows = new MetaGraphProvider(RestClient.builder()).leads(cfg(null), "form3");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", "l1").containsKey("field_data");
    }
}
