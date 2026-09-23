package com.bss.document.dto;

import com.bss.document.entity.ContentProviderConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A tenant's external CMS binding. The secret is a reference, never a value. */
@JsonPropertyOrder({"tenantId", "provider", "baseUrl", "projectId", "dataset", "secretRef",
        "webhookSecretRef", "directUrl", "@type"})
public record ContentProviderConfigView(
        String tenantId,
        String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL) String baseUrl,
        @JsonInclude(JsonInclude.Include.NON_NULL) String projectId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String dataset,
        @JsonInclude(JsonInclude.Include.NON_NULL) String secretRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) String webhookSecretRef,
        boolean directUrl,
        @JsonProperty("@type") String type) {

    public static ContentProviderConfigView of(ContentProviderConfig c) {
        return new ContentProviderConfigView(c.getTenantId(), c.getProvider(), c.getBaseUrl(),
                c.getProjectId(), c.getDataset(), c.getSecretRef(), c.getWebhookSecretRef(),
                c.isDirectUrl(), "ContentProviderConfig");
    }
}
