package com.bss.document.dto;

import com.bss.document.entity.StoredDocument;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One asset in the store. The bytes never ride the view — the channels wear
 * the {@code attachmentUrl}, which is the anonymous read path.
 */
@JsonPropertyOrder({"id", "href", "name", "category", "mimeType", "description", "link",
        "attachmentUrl", "@type"})
public record DocumentView(
        String id,
        String href,
        String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) String category,
        String mimeType,
        @JsonInclude(JsonInclude.Include.NON_NULL) String description,
        @JsonInclude(JsonInclude.Include.NON_NULL) String link,
        String attachmentUrl,
        @JsonProperty("@type") String type) {

    public static DocumentView of(StoredDocument d) {
        return new DocumentView(d.getId(), d.getHref(), d.getName(), d.getCategory(),
                d.getContentType(), d.getDescription(), d.getLink(),
                d.getHref() + "/content", "Document");
    }
}
