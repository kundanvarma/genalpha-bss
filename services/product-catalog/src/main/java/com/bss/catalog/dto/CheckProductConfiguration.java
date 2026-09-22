package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * TMF760 checkProductConfiguration, answered instantly: per item, accepted
 * with its price or rejected with the reasons (coded for machines, worded for
 * people), plus the order-ready configuration either way.
 */
@JsonPropertyOrder({"state", "instantSync", "result", "checkProductConfigurationItem", "@type"})
public record CheckProductConfiguration(String state, boolean instantSync, String result, List<Item> checkProductConfigurationItem,
        @JsonProperty("@type") String type) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"state", "configurationPrice", "message", "stateReason", "ruleName", "productConfiguration", "@type", "id"})
    public record Item(String id, String state, ConfigurationPriceSummary configurationPrice, List<String> message,
            List<StateReason> stateReason, String ruleName, OrderReadyConfiguration productConfiguration,
            @JsonProperty("@type") String type) {

        public boolean accepted() {
            return "accepted".equals(state);
        }
    }

    @JsonPropertyOrder({"code", "label"})
    public record StateReason(String code, String label) {
    }
}
