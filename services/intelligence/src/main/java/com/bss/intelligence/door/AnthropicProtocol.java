package com.bss.intelligence.door;

import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Anthropic Messages (#306): key in x-api-key, usage in input/output and prompt-cache tokens. */
final class AnthropicProtocol implements DoorProtocol {

    private static final String VERSION = "2023-06-01";

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public boolean serves(String tenantProvider) {
        return "anthropic".equalsIgnoreCase(tenantProvider);
    }

    @Override
    public String defaultBase() {
        return "https://api.anthropic.com";
    }

    @Override
    public String path() {
        return "/v1/messages";
    }

    @Override
    public void authenticate(HttpHeaders headers, String providerKey, Map<String, String> passthrough) {
        headers.set("x-api-key", providerKey);
        String version = passthrough.get("anthropic-version");
        headers.set("anthropic-version", version == null || version.isBlank() ? VERSION : version);
        String beta = passthrough.get("anthropic-beta");
        if (beta != null && !beta.isBlank()) {
            headers.set("anthropic-beta", beta);
        }
    }

    @Override
    public DoorPricing.Usage usage(JsonNode answer) {
        JsonNode u = answer.path("usage");
        return new DoorPricing.Usage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("cache_creation_input_tokens").asLong(0), u.path("cache_read_input_tokens").asLong(0));
    }

    @Override
    public String summary(JsonNode answer) {
        StringBuilder s = new StringBuilder("stop=").append(answer.path("stop_reason").asString(""));
        for (JsonNode block : answer.path("content")) {
            s.append(' ').append(block.path("type").asString(""));
        }
        return s.toString();
    }

    @Override
    public void redact(JsonNode request, Redactor redactor, Redaction map) {
        DoorRedaction.redactRequest(request, redactor, map);
    }

    @Override
    public void restore(JsonNode answer, Redaction map) {
        DoorRedaction.restoreResponse(answer, map);
    }

    @Override
    public String error(ObjectMapper json, Refusal kind, String message) {
        String type = switch (kind) {
            case AUTH -> "authentication_error";
            case BILLING -> "billing_error";
            case INVALID -> "invalid_request_error";
            case NOT_FOUND -> "not_found_error";
            case UPSTREAM, UNAVAILABLE -> "api_error";
        };
        return json.createObjectNode().put("type", "error")
                .set("error", json.createObjectNode().put("type", type).put("message", message)).toString();
    }
}
