package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The tenant's own brand card: name, colour, tagline — nothing operational. Absent fields are written as null. */
@JsonPropertyOrder({"id", "name", "color", "tagline"})
public record BrandView(String id, String name, String color, String tagline) {
}
