package com.bss.party.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** POST .../registryLink body: the registry's stable person id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryLinkRequest(String personRef) {
}
