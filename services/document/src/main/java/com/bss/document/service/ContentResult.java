package com.bss.document.service;

/**
 * How the read path serves a document: either the bytes (hosted store) or a
 * 302 to the external CMS/DAM's own URL (reference mode). The controller picks
 * the HTTP shape from {@link #isRedirect()}.
 */
public record ContentResult(byte[] bytes, String contentType, String redirectUrl) {

    public static ContentResult bytes(String contentType, byte[] bytes) {
        return new ContentResult(bytes, contentType, null);
    }

    public static ContentResult redirect(String url) {
        return new ContentResult(null, null, url);
    }

    public boolean isRedirect() {
        return redirectUrl != null;
    }
}
