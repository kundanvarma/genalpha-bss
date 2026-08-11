package com.bss.document.service;

import com.bss.document.entity.ContentProviderConfig;
import com.bss.document.entity.StoredDocument;
import com.bss.document.repository.ContentProviderConfigRepository;
import com.bss.document.repository.DocumentRepository;
import com.bss.document.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference-mode freshness. An external CMS calls the webhook when an asset
 * changes; possession of the tenant's shared secret (HMAC over the raw body,
 * Sanity's {@code t=<ts>,v1=<sig>} scheme) IS the authorization — an unknown
 * tenant, an unset secret, or a bad signature are all one indistinguishable 401.
 *
 * <p>A {@code delete} marks the referencing documents unavailable (the read path
 * then serves the placeholder rather than 302-ing to a now-gone CDN url); an
 * {@code upsert} restores them. Either bumps a cache-busting version. All of it
 * runs in the named tenant's RLS scope, so a webhook can only ever touch that
 * tenant's own rows.
 */
@Service
public class ContentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ContentWebhookService.class);

    private final DocumentRepository documents;
    private final ContentProviderConfigRepository configs;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentWebhookService(DocumentRepository documents, ContentProviderConfigRepository configs) {
        this.documents = documents;
        this.configs = configs;
    }

    @Transactional
    public Map<String, Object> handleSanity(String tenantId, byte[] rawBody, String signatureHeader) {
        try (TenantContext ignored = TenantContext.actAs(tenantId)) {
            ContentProviderConfig cfg = configs.findByTenantId(tenantId).orElse(null);
            String secret = cfg == null ? null : env(cfg.getWebhookSecretRef());
            if (secret == null || !"sanity".equals(cfg.getProvider())) {
                throw unauthorized("no sanity webhook secret for tenant");
            }
            verify(rawBody, signatureHeader, secret);

            JsonNode body = readBody(rawBody);
            String assetId = firstNonBlank(body.path("assetId").asText(null), body.path("_id").asText(null));
            if (assetId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook body needs assetId");
            }
            String operation = body.path("operation").asText("upsert");
            boolean deleted = "delete".equalsIgnoreCase(operation);

            String key = "ref:sanity:" + assetId;
            List<StoredDocument> hits = documents.findByTenantIdAndStorageKey(tenantId, key);
            for (StoredDocument doc : hits) {
                doc.setAvailable(!deleted);
                doc.setContentVersion(doc.getContentVersion() + 1);
                doc.setLastUpdate(OffsetDateTime.now());
            }
            documents.saveAll(hits);
            log.info("sanity webhook tenant={} op={} asset={} matched={}", tenantId, operation, assetId, hits.size());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tenantId", tenantId);
            result.put("operation", deleted ? "delete" : "upsert");
            result.put("assetId", assetId);
            result.put("matched", hits.size());
            return result;
        }
    }

    /** Sanity signature: header "t=<ms>,v1=<base64url>", sig = HMAC-SHA256(secret, "<t>.<body>"). */
    private void verify(byte[] body, String header, String secret) {
        if (header == null || header.isBlank()) {
            throw unauthorized("missing signature");
        }
        String ts = null;
        String provided = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                ts = kv[1];
            } else if (kv[0].equals("v1")) {
                provided = kv[1];
            }
        }
        if (ts == null || provided == null) {
            throw unauthorized("malformed signature");
        }
        String signingInput = ts + "." + new String(body, StandardCharsets.UTF_8);
        String expected = hmacBase64Url(secret, signingInput);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized("signature mismatch");
        }
    }

    private static String hmacBase64Url(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed: " + e.getMessage(), e);
        }
    }

    private JsonNode readBody(byte[] rawBody) {
        try {
            return mapper.readTree(rawBody == null || rawBody.length == 0 ? "{}".getBytes() : rawBody);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "webhook body is not JSON");
        }
    }

    private static String env(String ref) {
        return ref == null || ref.isBlank() ? null : System.getenv(ref);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    private static ResponseStatusException unauthorized(String why) {
        // One opaque 401 for every auth failure — never leak which tenants exist.
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "webhook not authorized (" + why + ")");
    }
}
