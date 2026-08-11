package com.bss.document.store;

/**
 * Where a referenced asset is served from — the external CMS/DAM's own
 * (CDN) URL, at a requested rendition. The document service redirects the
 * anonymous shop window here instead of proxying bytes.
 */
public record ResolvedAsset(String url) {
}
