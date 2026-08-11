package com.bss.document.store;

import com.bss.document.entity.ContentProviderConfig;

/**
 * The reference seam: one implementation per external headless CMS/DAM (or
 * one generic HTTP connector for the long tail). Unlike {@link ContentStore}
 * — which round-trips bytes for the HOSTED default — a provider stores a
 * reference and resolves the CMS's own delivery URL, so bytes never proxy
 * through BSS. Per-tenant config (projectId, dataset, secret-ref) is passed
 * in; providers are stateless singletons keyed by {@link #name()}.
 */
public interface AssetProvider {

    /** The provider id used in the {@code ref:<name>:<assetId>} storage key and config row. */
    String name();

    /**
     * Upload-through-BSS: push bytes to the CMS's asset API and return the
     * opaque asset id it minted. A pure reference (author-in-CMS) provider may
     * throw {@link UnsupportedOperationException}.
     */
    String upload(ContentProviderConfig config, String contentType, byte[] bytes);

    /**
     * Resolve the delivery URL for an asset id at a rendition
     * ({@code thumb|card|hero|orig}; null = orig). Called on the read path;
     * the service 302-redirects the browser here.
     */
    ResolvedAsset resolve(ContentProviderConfig config, String assetId, String rendition);
}
