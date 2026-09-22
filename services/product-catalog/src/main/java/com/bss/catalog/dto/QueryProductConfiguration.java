package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * TMF760 queryProductConfiguration, answered instantly: the configuration
 * space in the standard's v5 shape ({@code queryProductConfigurationItem}) and
 * in the house shape the first channels read ({@code computedProductConfigurationItem}).
 */
@JsonPropertyOrder({"id", "state", "instantSync", "queryProductConfigurationItem", "computedProductConfigurationItem", "@type"})
public record QueryProductConfiguration(String id, String state, boolean instantSync, List<Item> queryProductConfigurationItem,
        List<ComputedProductConfigurationItem> computedProductConfigurationItem, @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"id", "state", "productConfiguration", "@type"})
    public record Item(String id, String state, ProductConfiguration productConfiguration, @JsonProperty("@type") String type) {
    }
}
