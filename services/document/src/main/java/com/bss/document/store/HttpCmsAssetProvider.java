package com.bss.document.store;

import com.bss.document.entity.ContentProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The generic, config-driven HTTP connector — one class for the long tail of
 * headless CMS/DAMs, NO per-vendor code. Everything vendor-specific lives in the
 * tenant's {@code config} JSON, so pointing at a differently-shaped CMS (Strapi's
 * multipart upload + array response + relative /uploads URLs, versus Sanity's raw
 * upload + one CDN url) is a config row, not a new adapter.
 *
 * <p>config keys (all optional but upload needs uploadUrl):
 * <pre>
 * {
 *   "uploadUrl":    "http://strapi:1337/api/upload",
 *   "uploadMode":   "multipart" | "raw",     // how the bytes are sent
 *   "fileField":    "files",                  // multipart field name
 *   "authHeader":   "Authorization",          // header carrying the token (value = authPrefix + secret)
 *   "authPrefix":   "Bearer ",
 *   "assetIdPath":  "/0/url",                 // JSON pointer into the upload response -> stored asset ref
 *   "resolveBase":  "http://strapi:1337",     // prepended to a RELATIVE asset ref at resolve
 *   "renditionMode":"query" | "none",         // query = append ?w=; none = original only
 *   "renditionParam":"w"
 * }
 * </pre>
 * The write token (if any) is read from the env var named by the binding's
 * {@code secretRef} — never stored.
 */
@Component
public class HttpCmsAssetProvider implements AssetProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpCmsAssetProvider.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "http";
    }

    @Override
    public String upload(ContentProviderConfig cfg, String contentType, byte[] bytes) {
        JsonNode c = config(cfg);
        String uploadUrl = text(c, "uploadUrl", null);
        if (uploadUrl == null) {
            throw new IllegalStateException("http connector needs config.uploadUrl");
        }
        boolean multipart = !"raw".equalsIgnoreCase(text(c, "uploadMode", "multipart"));
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uploadUrl))
                    .timeout(Duration.ofSeconds(20));
            String token = token(cfg);
            String authHeader = text(c, "authHeader", null);
            if (authHeader != null && token != null && !token.isBlank()) {
                builder.header(authHeader, text(c, "authPrefix", "Bearer ") + token);
            }
            if (multipart) {
                String boundary = "bssX" + Long.toHexString(System.nanoTime());
                byte[] body = multipartBody(boundary, text(c, "fileField", "files"), contentType, bytes);
                builder.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                builder.header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("CMS upload HTTP " + resp.statusCode() + ": " + resp.body());
            }
            String assetIdPath = text(c, "assetIdPath", "/url");
            JsonNode extracted = mapper.readTree(resp.body()).at(assetIdPath);
            if (extracted.isMissingNode() || extracted.asText("").isBlank()) {
                throw new IllegalStateException("upload response has nothing at assetIdPath " + assetIdPath);
            }
            return extracted.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("CMS upload to " + uploadUrl + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ResolvedAsset resolve(ContentProviderConfig cfg, String assetId, String rendition) {
        JsonNode c = config(cfg);
        String url = assetId.startsWith("http://") || assetId.startsWith("https://")
                ? assetId
                : trimSlash(text(c, "resolveBase", "")) + ensureLeadingSlash(assetId);
        if ("query".equalsIgnoreCase(text(c, "renditionMode", "none"))) {
            Rendition r = Rendition.parse(rendition);
            if (r.sized()) {
                String param = text(c, "renditionParam", "w");
                url += (url.contains("?") ? "&" : "?") + param + "=" + r.width();
            }
        }
        return new ResolvedAsset(url);
    }

    private JsonNode config(ContentProviderConfig cfg) {
        try {
            String json = cfg.getConfig();
            return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("http connector config is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** RFC 7578 multipart/form-data with a single file part. */
    private static byte[] multipartBody(String boundary, String field, String contentType, byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String filename = "upload." + ext(contentType);
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("multipart build failed: " + e.getMessage(), e);
        }
    }

    private static String ext(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }

    private static String token(ContentProviderConfig cfg) {
        if (cfg.getSecretRef() == null || cfg.getSecretRef().isBlank()) {
            return null;
        }
        String token = System.getenv(cfg.getSecretRef());
        if (token == null) {
            log.warn("http connector secretRef '{}' names an env var that is not set", cfg.getSecretRef());
        }
        return token;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String ensureLeadingSlash(String s) {
        return s.startsWith("/") ? s : "/" + s;
    }
}
