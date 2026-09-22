package com.bss.intelligence.service;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One turn of a chat-to-create copilot (product or journey): a question,
 * advice, or a PROPOSAL. The proposal is the model's own document — the
 * console validates it and a deterministic executor applies it on a click —
 * so it stays open ({@link JsonNode}), as does any other key the model wrote
 * beside the contract ({@code extensions}). A forecast rides along when the
 * commercial simulator could score the proposal.
 */
@JsonPropertyOrder({"kind", "message", "proposal", "provider", "model", "forecast"})
public record CopilotReply(String kind, String message, JsonNode proposal, String provider, String model,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode forecast,
        @JsonAnyGetter Map<String, Object> extensions) {

    public CopilotReply {
        extensions = extensions == null ? new LinkedHashMap<>() : extensions;
    }
}
