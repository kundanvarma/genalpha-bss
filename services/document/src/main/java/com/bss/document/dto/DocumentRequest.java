package com.bss.document.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A posted asset: metadata plus the bytes, base64. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentRequest(
        String name,
        String mimeType,
        String content,
        String category,
        String description,
        String link) {
}
