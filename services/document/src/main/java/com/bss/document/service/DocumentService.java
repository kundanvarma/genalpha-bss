package com.bss.document.service;

import com.bss.document.api.ApiConstants;
import com.bss.document.entity.ContentProviderConfig;
import com.bss.document.entity.StoredDocument;
import com.bss.document.exception.BadRequestException;
import com.bss.document.exception.NotFoundException;
import com.bss.document.repository.DocumentRepository;
import com.bss.document.security.TenantScope;
import com.bss.document.store.AssetProvider;
import com.bss.document.store.AssetProviderRegistry;
import com.bss.document.store.ContentStore;
import com.bss.document.store.ResolvedAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * TMF667: the content the channels wear — logos, offering art, banners.
 * Back office uploads (base64 in, image types only); serving the bytes is
 * the anonymous shop window. In-row storage keeps dev simple; the API is
 * the seam a cloud deployment points at object storage.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** Channel media only — this is a brand asset store, not a file dump. */
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/svg+xml", "image/png", "image/jpeg", "image/webp");
    private static final int MAX_BYTES = 512 * 1024;

    /** Served when a referenced asset can't be resolved — never a broken image. */
    private static final byte[] PLACEHOLDER = ("<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\"640\" height=\"440\" viewBox=\"0 0 640 440\"><rect width=\"640\" height=\"440\" "
            + "fill=\"#e6edf0\"/><text x=\"320\" y=\"228\" font-family=\"sans-serif\" font-size=\"22\" "
            + "fill=\"#7a8b93\" text-anchor=\"middle\">image unavailable</text></svg>")
            .getBytes(StandardCharsets.UTF_8);

    private final DocumentRepository repository;
    private final TenantScope tenantScope;
    private final ContentStore contentStore;
    private final ContentProviderConfigService providerConfigs;
    private final AssetProviderRegistry providers;

    public DocumentService(DocumentRepository repository, TenantScope tenantScope,
            ContentStore contentStore, ContentProviderConfigService providerConfigs,
            AssetProviderRegistry providers) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.contentStore = contentStore;
        this.providerConfigs = providerConfigs;
        this.providers = providers;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("mimeType") == null || dto.get("content") == null) {
            throw new BadRequestException("name, mimeType and content (base64) are required");
        }
        String mimeType = String.valueOf(dto.get("mimeType"));
        if (!IMAGE_TYPES.contains(mimeType)) {
            throw new BadRequestException("mimeType must be one of " + IMAGE_TYPES);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(String.valueOf(dto.get("content")));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("content is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BadRequestException("content must be 1B..512KB");
        }
        StoredDocument entity = new StoredDocument();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/document/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setCategory(dto.get("category") == null ? null : String.valueOf(dto.get("category")));
        entity.setContentType(mimeType);
        // Reference mode: a tenant bound to an external CMS uploads THERE and the
        // row keeps only a ref:<provider>:<assetId> key. Otherwise the hosted
        // ContentStore (in-row/S3/Azure) takes the bytes as before.
        Optional<ContentProviderConfig> external = providerConfigs.forCurrentTenant();
        AssetProvider provider = external.map(c -> providers.get(c.getProvider())).orElse(null);
        if (provider != null) {
            String assetId = provider.upload(external.get(), mimeType, bytes);
            entity.setStorageKey("ref:" + external.get().getProvider() + ":" + assetId);
        } else {
            String storageKey = contentStore.put(entity.getTenantId(), id, mimeType, bytes);
            if (storageKey.startsWith("row:")) {
                entity.setContent(bytes);
            } else {
                // the bytes live in the object store; the row keeps the key
                entity.setStorageKey(storageKey);
            }
        }
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String category) {
        String tenant = tenantScope.currentTenantId();
        List<StoredDocument> rows = category != null
                ? repository.findByTenantIdAndCategory(tenant, category)
                : repository.findByTenantId(tenant);
        return rows.stream().map(this::toMap).toList();
    }

    /** Stable brand asset: the newest 'brand' document of the request's tenant. */
    @Transactional(readOnly = true)
    public StoredDocument brandLogo() {
        return hydrate(repository.findByTenantIdAndCategory(tenantScope.currentTenantId(), "brand")
                .stream().reduce((a, b) -> b)
                .orElseThrow(() -> NotFoundException.forResource("Document", "brand-logo")));
    }

    /**
     * The read path. A hosted document serves its bytes; a reference document
     * resolves the external CMS/DAM's own URL and is served as a 302 redirect
     * (the browser hits the CMS CDN, not us). Any resolve failure falls open to
     * a placeholder — never a broken image, never a 500.
     */
    @Transactional(readOnly = true)
    public ContentResult resolveContent(String id, String rendition) {
        StoredDocument doc = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Document", id));
        String key = doc.getStorageKey();
        if (key != null && key.startsWith("ref:")) {
            return resolveReference(doc, key, rendition);
        }
        hydrate(doc);
        return ContentResult.bytes(doc.getContentType(), doc.getContent());
    }

    private ContentResult resolveReference(StoredDocument doc, String key, String rendition) {
        // A webhook may have reported the referenced asset gone — serve the
        // placeholder rather than 302-ing to a URL the CMS no longer backs.
        if (!doc.isAvailable()) {
            return ContentResult.bytes("image/svg+xml", PLACEHOLDER);
        }
        try {
            String[] parts = key.split(":", 3);   // ref, provider, assetId
            if (parts.length < 3) {
                throw new IllegalStateException("malformed reference key");
            }
            ContentProviderConfig cfg = providerConfigs.forCurrentTenant().orElse(null);
            AssetProvider provider = cfg != null && cfg.getProvider().equals(parts[1])
                    ? providers.get(parts[1]) : null;
            if (provider == null) {
                throw new IllegalStateException("no provider bound for reference '" + parts[1] + "'");
            }
            String url = provider.resolve(cfg, parts[2], rendition).url();
            // Cache-bust: a webhook upsert bumps the version so a replaced asset
            // isn't masked by a stale cached redirect. (Bounded by the 302 TTL.)
            if (doc.getContentVersion() > 0) {
                url += (url.contains("?") ? "&" : "?") + "v=" + doc.getContentVersion();
            }
            return ContentResult.redirect(url);
        } catch (RuntimeException e) {
            log.warn("reference resolve failed for document {} ({}): {}", doc.getId(), key, e.toString());
            return ContentResult.bytes("image/svg+xml", PLACEHOLDER);
        }
    }

    /** Externally-stored bytes are fetched on read; in-row rows already
     * carry them. The caller never knows the difference. */
    private StoredDocument hydrate(StoredDocument entity) {
        if (entity.getContent() == null && entity.getStorageKey() != null) {
            entity.setContent(contentStore.get(entity.getTenantId(), entity.getStorageKey()));
        }
        return entity;
    }

    private Map<String, Object> toMap(StoredDocument d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", d.getId());
        map.put("href", d.getHref());
        map.put("name", d.getName());
        if (d.getCategory() != null) map.put("category", d.getCategory());
        map.put("mimeType", d.getContentType());
        map.put("attachmentUrl", d.getHref() + "/content");
        map.put("@type", "Document");
        return map;
    }
}
