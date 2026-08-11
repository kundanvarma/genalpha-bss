package com.bss.document.store;

import com.bss.document.entity.ContentProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sanity as an external asset provider (the first named adapter). Upload goes to
 * the Assets API and returns Sanity's asset id (image-&lt;hash&gt;-&lt;w&gt;x&lt;h&gt;-&lt;fmt&gt;);
 * resolve reconstructs the {@code cdn.sanity.io} URL from that id + the tenant's
 * projectId/dataset and appends the rendition's transform params — so the id is
 * stored, the URL is resolved at read (no stale/env-specific URL in the catalog).
 *
 * <p>Per-tenant config: {@code projectId}, {@code dataset}, and {@code secretRef}
 * (env var holding the write token) come from the tenant's binding row.
 * {@code baseUrl}, when set, overrides both the API and CDN host — that's how a
 * self-hosted Sanity or the mock is pointed at; unset means the real hosts.
 */
@Component
public class SanityAssetProvider implements AssetProvider {

    private static final Logger log = LoggerFactory.getLogger(SanityAssetProvider.class);
    private static final String API_VERSION = "v2021-06-07";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "sanity";
    }

    @Override
    public String upload(ContentProviderConfig cfg, String contentType, byte[] bytes) {
        String dataset = dataset(cfg);
        String url = apiHost(cfg) + "/" + API_VERSION + "/assets/images/" + dataset;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            String token = token(cfg);
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Sanity upload HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode doc = mapper.readTree(resp.body()).path("document");
            String assetId = doc.path("_id").asText(null);
            if (assetId == null || assetId.isBlank()) {
                throw new IllegalStateException("Sanity upload returned no document._id");
            }
            return assetId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Sanity upload to " + url + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ResolvedAsset resolve(ContentProviderConfig cfg, String assetId, String rendition) {
        String base = cdnUrl(cfg, assetId);
        String params = renditionParams(rendition);
        return new ResolvedAsset(params.isEmpty() ? base : base + "?" + params);
    }

    /** Build the CDN image URL from a Sanity asset id; pass through if it's already a URL. */
    private String cdnUrl(ContentProviderConfig cfg, String assetId) {
        if (assetId.startsWith("http://") || assetId.startsWith("https://")) {
            return assetId;                                   // defensive: already a URL
        }
        String body = assetId.startsWith("image-") ? assetId.substring("image-".length()) : assetId;
        int fmtDash = body.lastIndexOf('-');
        int dimDash = fmtDash > 0 ? body.lastIndexOf('-', fmtDash - 1) : -1;
        if (fmtDash < 0 || dimDash < 0) {
            throw new IllegalStateException("unrecognised Sanity asset id: " + assetId);
        }
        String fmt = body.substring(fmtDash + 1);
        String dims = body.substring(dimDash + 1, fmtDash);
        String hash = body.substring(0, dimDash);
        String file = hash + "-" + dims + "." + fmt;
        return cdnHost(cfg) + "/images/" + projectId(cfg) + "/" + dataset(cfg) + "/" + file;
    }

    /** Portable rendition → Sanity transform query (auto format + max fit). */
    private static String renditionParams(String rendition) {
        Rendition r = Rendition.parse(rendition);
        return r.sized() ? "w=" + r.width() + "&fit=max&auto=format" : "";
    }

    private static String apiHost(ContentProviderConfig cfg) {
        if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
            return trimSlash(cfg.getBaseUrl());
        }
        return "https://" + projectId(cfg) + ".api.sanity.io";
    }

    private static String cdnHost(ContentProviderConfig cfg) {
        if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
            return trimSlash(cfg.getBaseUrl());              // self-host/mock serves both
        }
        return "https://cdn.sanity.io";
    }

    private static String projectId(ContentProviderConfig cfg) {
        if (cfg.getProjectId() == null || cfg.getProjectId().isBlank()) {
            throw new IllegalStateException("Sanity provider needs a projectId");
        }
        return cfg.getProjectId();
    }

    private static String dataset(ContentProviderConfig cfg) {
        return cfg.getDataset() == null || cfg.getDataset().isBlank() ? "production" : cfg.getDataset();
    }

    /** The write token is read from the env var named by secretRef — never the DB. */
    private static String token(ContentProviderConfig cfg) {
        if (cfg.getSecretRef() == null || cfg.getSecretRef().isBlank()) {
            return null;
        }
        String token = System.getenv(cfg.getSecretRef());
        if (token == null) {
            log.warn("Sanity secretRef '{}' names an env var that is not set", cfg.getSecretRef());
        }
        return token;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
