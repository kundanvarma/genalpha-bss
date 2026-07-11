package com.bss.document.service;

import com.bss.document.api.ApiConstants;
import com.bss.document.entity.StoredDocument;
import com.bss.document.exception.BadRequestException;
import com.bss.document.exception.NotFoundException;
import com.bss.document.repository.DocumentRepository;
import com.bss.document.security.TenantScope;
import com.bss.document.store.ContentStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Channel media only — this is a brand asset store, not a file dump. */
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/svg+xml", "image/png", "image/jpeg", "image/webp");
    private static final int MAX_BYTES = 512 * 1024;

    private final DocumentRepository repository;
    private final TenantScope tenantScope;
    private final ContentStore contentStore;

    public DocumentService(DocumentRepository repository, TenantScope tenantScope,
            ContentStore contentStore) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.contentStore = contentStore;
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
        entity.setContent(bytes);
        contentStore.put(entity.getTenantId(), id, mimeType, bytes);
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
        return repository.findByTenantIdAndCategory(tenantScope.currentTenantId(), "brand").stream()
                .reduce((a, b) -> b)
                .orElseThrow(() -> NotFoundException.forResource("Document", "brand-logo"));
    }

    @Transactional(readOnly = true)
    public StoredDocument content(String id) {
        return repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Document", id));
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
