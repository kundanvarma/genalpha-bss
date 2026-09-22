package com.bss.catalog.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * A picker: a spec characteristic a customer may choose, with its declared
 * values (exact values or ranges, each saying whether stock lets it be picked).
 * The values are the specification's own objects — the open edge.
 */
@JsonPropertyOrder({"name", "valueType", "configurable", "productSpecCharacteristicValue"})
public record ConfigurableCharacteristic(String name, String valueType, boolean configurable,
        List<Map<String, Object>> productSpecCharacteristicValue) {
}
