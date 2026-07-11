package com.bss.document.store;

/**
 * Where the bytes live. The TMF667 API stays the same whatever sits behind
 * it: the in-row store ships by default; pointing an operator's Sanity,
 * Contentful or S3 at the same API is one adapter class implementing this —
 * the same seam pattern as the payment PSP and the IdP admin client.
 */
public interface ContentStore {

    /** Persist and return an opaque storage key. */
    String put(String tenantId, String documentId, String contentType, byte[] bytes);

    byte[] get(String tenantId, String storageKey);
}
