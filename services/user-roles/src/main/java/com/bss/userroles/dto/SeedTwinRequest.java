package com.bss.userroles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST seedTwinBase: which operator's aggregate shape to mirror (default genalpha) and how many twins (default 25). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeedTwinRequest(String sourceId, Integer count) {
}
