package com.bss.catalog.pim;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bring-your-own-PIM: resolves an offering's media from the tenant's external
 * product-information system. The wire contract is deliberately small — the
 * kind of endpoint every PIM can front with a view:
 *
 *   GET {pim-base-url}/media?product={offering name}
 *   -> { "media": [ { "role": "front", "type": "image/svg+xml",
 *                     "href": "/pim/assets/....svg" }, ... ] }
 *
 * Media are keyed by PRODUCT NAME, not our offering id: the operator's PIM
 * predates this BSS and has never heard of our UUIDs. Responses cache for a
 * minute per offering; 404 means "this product isn't content-managed there"
 * and any failure at all falls open to the catalog's own stored attachments.
 */
@Component
public class ExternalPimContentSource implements ProductContentSource {

    private static final Logger log = LoggerFactory.getLogger(ExternalPimContentSource.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final TenantRegistry tenants;
    private final RestClient rest;
    private final Map<String, CachedMedia> cache = new ConcurrentHashMap<>();

    private record CachedMedia(List<Map<String, Object>> attachments, long expiresAt) {
    }

    public ExternalPimContentSource(TenantRegistry tenants) {
        this.tenants = tenants;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<Map<String, Object>> attachmentsFor(String tenantId, ProductOfferingDto offering) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        String base = tenant == null ? null : tenant.getPimBaseUrl();
        if (base == null || base.isBlank() || offering.getName() == null) {
            return null;
        }
        String key = tenantId + ":" + offering.getId();
        CachedMedia cached = cache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.attachments();
        }
        List<Map<String, Object>> resolved = fetch(base, offering.getName());
        cache.put(key, new CachedMedia(resolved, System.currentTimeMillis() + CACHE_TTL.toMillis()));
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetch(String base, String productName) {
        try {
            Map<String, Object> body = rest.get()
                    .uri(base + "/media?product=" + URLEncoder.encode(productName, StandardCharsets.UTF_8))
                    .retrieve().body(Map.class);
            if (body == null || !(body.get("media") instanceof List<?> media)) {
                return null;
            }
            List<Map<String, Object>> attachments = new ArrayList<>();
            for (Map<String, Object> m : (List<Map<String, Object>>) media) {
                if (m.get("href") == null) {
                    continue;
                }
                Map<String, Object> attachment = new LinkedHashMap<>();
                attachment.put("name", m.getOrDefault("role", "image"));
                attachment.put("mimeType", m.getOrDefault("type", "image/svg+xml"));
                attachment.put("url", m.get("href"));
                attachment.put("@type", "Attachment");
                attachments.add(attachment);
            }
            return attachments.isEmpty() ? null : attachments;
        } catch (RuntimeException e) {
            // 404 = not content-managed there; anything else = PIM down.
            // Either way the stored attachments stand — content never blocks commerce.
            log.debug("external PIM had nothing for '{}': {}", productName, e.getMessage());
            return null;
        }
    }
}
