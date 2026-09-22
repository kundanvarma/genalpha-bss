package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A ProductConfiguration as a channel sends it to the configurator: the
 * offering, the picked bundle options, the characteristic picks, a quantity,
 * and priceOnly when billing prices an installed product as configured.
 * The picks are genuinely open — the house {name, value} list, the v5
 * selected-value list, or the ontology's flat "screens=5+, extraProfiles=6"
 * string — so they stay a JSON node until {@code picks()} reads them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductConfigurationRequest(EntityRef productOffering, List<EntityRef> selectedOption,
        JsonNode configurationCharacteristic, Integer quantity, Boolean priceOnly) {

    public static final ProductConfigurationRequest EMPTY = new ProductConfigurationRequest(null, null, null, null, null);

    public List<EntityRef> selectedOptionOrEmpty() {
        return selectedOption == null ? List.of() : selectedOption;
    }

    public int quantityOr(int dflt) {
        return quantity == null ? dflt : quantity;
    }

    public boolean isPriceOnly() {
        return Boolean.TRUE.equals(priceOnly);
    }

    /** The body of queryProductConfiguration: {productConfiguration: {productOffering}} or {productOffering} flat. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Query(ProductConfigurationRequest productConfiguration, EntityRef productOffering) {

        public ProductConfigurationRequest configuration() {
            return productConfiguration != null ? productConfiguration
                    : new ProductConfigurationRequest(productOffering, null, null, null, null);
        }
    }

    /** The body of checkProductConfiguration: one item per configuration to check. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Check(List<Item> checkProductConfigurationItem) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(String id, ProductConfigurationRequest productConfiguration) {

            public ProductConfigurationRequest configuration() {
                return productConfiguration == null ? EMPTY : productConfiguration;
            }
        }
    }
}
