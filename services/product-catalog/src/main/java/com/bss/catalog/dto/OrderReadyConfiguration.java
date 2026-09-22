package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The configuration echoed back order-ready: the offering, the quantity, the
 * picked options (each with the picks its own spec owns), the bundle's own
 * picks, and — when accepted — the evaluated prices and implied actions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"productOffering", "quantity", "selectedOption", "configurationCharacteristic", "@type", "configurationPrice",
        "configurationAction"})
public record OrderReadyConfiguration(EntityRef productOffering, int quantity, List<SelectedOption> selectedOption,
        List<NameValue> configurationCharacteristic, List<ConfigurationPrice> configurationPrice,
        List<ConfigurationAction> configurationAction, @JsonProperty("@type") String type) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "characteristic"})
    public record SelectedOption(String id, String name, List<NameValue> characteristic) {
    }
}
