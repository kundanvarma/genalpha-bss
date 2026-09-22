package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * TMF760 v5 ProductConfiguration — the configuration space in the standard's
 * shape: characteristics with selectable values, prices, terms, the actions a
 * relationship implies, and child configurations for bundle members.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"productOffering", "productSpecification", "quantity", "isSelectable", "isSelected", "isVisible",
        "configurationCharacteristic", "configurationPrice", "configurationTerm", "configurationAction", "productConfiguration", "@type"})
public record ProductConfiguration(EntityRef productOffering, EntityRef productSpecification, int quantity, boolean isSelectable,
        boolean isSelected, boolean isVisible, List<Characteristic> configurationCharacteristic,
        List<ConfigurationPrice> configurationPrice, List<Map<String, Object>> configurationTerm,
        List<ConfigurationAction> configurationAction, List<Child> productConfiguration, @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"id", "name", "valueType", "isConfigurable", "minCardinality", "maxCardinality",
            "configurationCharacteristicValue", "@type"})
    public record Characteristic(String id, String name, String valueType, boolean isConfigurable, int minCardinality,
            int maxCardinality, List<CharacteristicValue> configurationCharacteristicValue, @JsonProperty("@type") String type) {
    }

    /** One selectable value; ranges carry their bounds, exact values their characteristicValue. Bounds and units are as declared. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"isSelectable", "isSelected", "valueFrom", "valueTo", "rangeInterval", "unitOfMeasure", "regex",
            "characteristicValue", "@type"})
    public record CharacteristicValue(Object isSelectable, boolean isSelected, Object valueFrom, Object valueTo, Object rangeInterval,
            Object unitOfMeasure, Object regex, NameValue characteristicValue, @JsonProperty("@type") String type) {
    }

    /** A bundle member as a child configuration: fixed (not selectable) or one option of a choice group. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"productOffering", "isSelectable", "isSelected", "bundledProductOfferingOption", "bundledGroupProductOffering"})
    public record Child(EntityRef productOffering, boolean isSelectable, boolean isSelected, Cardinality bundledProductOfferingOption,
            GroupCardinality bundledGroupProductOffering) {
    }

    @JsonPropertyOrder({"numberRelOfferLowerLimit", "numberRelOfferUpperLimit"})
    public record Cardinality(long numberRelOfferLowerLimit, long numberRelOfferUpperLimit) {
    }

    @JsonPropertyOrder({"name", "numberRelOfferLowerLimit", "numberRelOfferUpperLimit"})
    public record GroupCardinality(String name, long numberRelOfferLowerLimit, long numberRelOfferUpperLimit) {
    }
}
