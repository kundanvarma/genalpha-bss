package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The house view of a configuration space: fixed bundle members, choice
 * groups with their options, the offering's own pickers and prices, terms,
 * relationships, whether units are fungible and what stock says. Terms and
 * relationships are the TMF620 objects as stored — open by design.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"productOffering", "isBundle", "fixedMember", "choiceGroup", "configurationCharacteristic", "price",
        "productOfferingTerm", "productOfferingRelationship", "fungible", "availability", "@type"})
public record ComputedProductConfigurationItem(EntityRef productOffering, boolean isBundle, List<FixedMember> fixedMember,
        List<ChoiceGroup> choiceGroup, List<ConfigurableCharacteristic> configurationCharacteristic, List<PriceView> price,
        List<Map<String, Object>> productOfferingTerm, List<Map<String, Object>> productOfferingRelationship,
        boolean fungible, Availability availability, @JsonProperty("@type") String type) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "minCardinality", "maxCardinality", "@type"})
    public record FixedMember(String id, String name, long minCardinality, long maxCardinality, @JsonProperty("@type") String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"name", "minSelections", "maxSelections", "default", "option", "@type"})
    public record ChoiceGroup(String name, long minSelections, long maxSelections, @JsonProperty("default") String defaultOption,
            List<ChoiceOption> option, @JsonProperty("@type") String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "configurationCharacteristic", "price", "@referredType"})
    public record ChoiceOption(String id, String name, List<ConfigurableCharacteristic> configurationCharacteristic,
            List<PriceView> price, @JsonProperty("@referredType") String referredType) {
    }
}
