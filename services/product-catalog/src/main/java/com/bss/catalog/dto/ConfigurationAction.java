package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What a relationship asks of the configuration: add the required offering (auto-added, prompted, or blocking). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"action", "description", "productOffering", "isSelected", "role"})
public record ConfigurationAction(String action, String description, EntityRef productOffering, boolean isSelected, String role) {
}
