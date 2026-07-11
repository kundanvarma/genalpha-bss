package com.bss.document.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Default store: the bytes ride in the document row itself (the service
 * layer persists them; the storage key is just a marker). Dev-simple,
 * transactional, and honest about its ceiling — big libraries belong in an
 * object-store adapter.
 */
@Component
@ConditionalOnProperty(name = "bss.content.store", havingValue = "in-row", matchIfMissing = true)
public class InRowContentStore implements ContentStore {

    @Override
    public String put(String tenantId, String documentId, String contentType, byte[] bytes) {
        return "row:" + documentId;
    }

    @Override
    public byte[] get(String tenantId, String storageKey) {
        throw new UnsupportedOperationException("in-row content is read from the entity");
    }
}
