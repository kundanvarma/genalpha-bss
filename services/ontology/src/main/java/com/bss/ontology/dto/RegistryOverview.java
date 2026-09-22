package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** The registry at a glance for the caller's tenant: what it holds, in what layers, under which rule. */
@JsonPropertyOrder({"name", "registry", "tenant", "tenantOverlay", "concepts", "actions", "capabilities", "components", "agents", "layers", "rule"})
public record RegistryOverview(String name, String registry, String tenant, boolean tenantOverlay, int concepts, int actions,
        int capabilities, int components, int agents, List<String> layers, String rule) {
}
