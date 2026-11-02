package com.bss.ontology.service;

import com.bss.ontology.dto.McpMessages;
import com.bss.ontology.registry.Registry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The MCP resources: the model itself, so an agent can read what a Customer or a
 * Subscription IS — its meaning, states, properties and links — before it calls a
 * tool that acts on one. One resource per concept of the caller's tenant (core plus
 * overlay), {@code ontology://concept/<Name>}, as JSON; plus the MCP Apps cards.
 */
@Service
public class McpResources {

    public static final String CONCEPT_PREFIX = "ontology://concept/";
    private static final String JSON = "application/json";

    private final Registry registry;
    private final McpApps apps;
    private final ObjectMapper json;

    public McpResources(Registry registry, McpApps apps, ObjectMapper json) {
        this.registry = registry;
        this.apps = apps;
        this.json = json;
    }

    public McpMessages.ResourceList list(String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        List<McpMessages.Resource> out = new ArrayList<>();
        for (JsonNode c : l.concepts().values()) {
            String name = c.path("concept").asString();
            out.add(new McpMessages.Resource(CONCEPT_PREFIX + name, name, name, c.path("meaning").asString(""), JSON, null));
        }
        out.addAll(apps.resources(l.actions()));
        return new McpMessages.ResourceList(out);
    }

    /** One resource's contents; an unknown URI is an invalid parameter (MCP 2026-07-28 retired -32002). */
    public McpMessages.ResourceRead read(String uri, String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        if (uri.startsWith(CONCEPT_PREFIX)) {
            JsonNode concept = l.concepts().get(uri.substring(CONCEPT_PREFIX.length()));
            if (concept != null) {
                return new McpMessages.ResourceRead(List.of(new McpMessages.ResourceContent(uri, JSON,
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(concept), null)));
            }
        }
        Optional<McpMessages.ResourceContent> card = apps.read(uri, l.actions());
        if (card.isPresent()) {
            return new McpMessages.ResourceRead(List.of(card.get()));
        }
        throw new IllegalArgumentException("Resource not found: " + uri);
    }
}
