package com.bss.userroles.dto;

import com.bss.userroles.security.TenantRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One operator the registry knows — the console's list row. */
@JsonPropertyOrder({"id", "name", "locale", "currency", "agentCommerce", "issuer", "@type"})
public record OperatorView(String id, String name, String locale, String currency, String agentCommerce,
        String issuer, @JsonProperty("@type") String type) {

    public static OperatorView of(TenantRegistry.TenantEntry t) {
        return new OperatorView(t.getId(), t.getBrandName() == null ? t.getId() : t.getBrandName(), t.getLocale(),
                t.getCurrency(), t.getAgentCommerce(), t.getIssuer(), "Operator");
    }
}
