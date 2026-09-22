package com.bss.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** TMF678 billDocument: the bill's rendered PDF, addressable. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "mimeType", "@type", "href", "url"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachmentRef(String id, String name, String mimeType,
        @JsonProperty("@type") String type, String href, String url) {

    public static AttachmentRef pdfOf(String billId, String billNo, String billHref) {
        return new AttachmentRef(billId + "-document", "Bill " + billNo, "application/pdf",
                "AttachmentRefOrValue", billHref + "/document.pdf", billHref + "/document.pdf");
    }
}
