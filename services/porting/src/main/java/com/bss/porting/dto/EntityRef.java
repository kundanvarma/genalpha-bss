package com.bss.porting.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The product order a port belongs to — an id and nothing more. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityRef(String id) {
}
