package com.bss.intelligence.door;

import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * OpenAI Chat Completions (#309) — which every OpenAI-compatible endpoint speaks
 * too: a local model server, or a provider's own compatibility endpoint. The key
 * is a Bearer token; usage is prompt/completion tokens, with the cached part of
 * the prompt reported INSIDE prompt_tokens. Text sits in message content (a
 * string, or an array of text parts), and a tool call's arguments arrive as a
 * JSON STRING — redacted and restored as text, which keeps the JSON valid because
 * a placeholder carries no quotes.
 */
final class OpenAiProtocol implements DoorProtocol {

    private static final Set<String> PROVIDERS = Set.of("openai", "openai-compatible");

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public boolean serves(String tenantProvider) {
        return tenantProvider != null && PROVIDERS.contains(tenantProvider.toLowerCase());
    }

    @Override
    public String defaultBase() {
        return "https://api.openai.com/v1";
    }

    @Override
    public String path() {
        return "/chat/completions";
    }

    @Override
    public void authenticate(HttpHeaders headers, String providerKey, Map<String, String> passthrough) {
        headers.setBearerAuth(providerKey);
    }

    @Override
    public DoorPricing.Usage usage(JsonNode answer) {
        JsonNode u = answer.path("usage");
        long prompt = u.path("prompt_tokens").asLong(0);
        long cached = Math.min(prompt, u.path("prompt_tokens_details").path("cached_tokens").asLong(0));
        return new DoorPricing.Usage(prompt - cached, u.path("completion_tokens").asLong(0), 0, cached);
    }

    @Override
    public String summary(JsonNode answer) {
        StringBuilder s = new StringBuilder();
        for (JsonNode choice : answer.path("choices")) {
            s.append("finish=").append(choice.path("finish_reason").asString(""));
            int calls = choice.path("message").path("tool_calls").size();
            if (calls > 0) {
                s.append(" tool_calls=").append(calls);
            }
        }
        return s.toString();
    }

    @Override
    public void redact(JsonNode request, Redactor redactor, Redaction map) {
        UnaryOperator<String> redact = text -> redactor.redact(text, map);
        for (JsonNode message : request.path("messages")) {
            if (message instanceof ObjectNode m) {
                content(m, redact);
                toolCalls(m, redact);
            }
        }
    }

    @Override
    public void restore(JsonNode answer, Redaction map) {
        UnaryOperator<String> restore = map::restore;
        for (JsonNode choice : answer.path("choices")) {
            if (choice.path("message") instanceof ObjectNode m) {
                content(m, restore);
                toolCalls(m, restore);
            }
        }
    }

    /** A message's content: a string, or an array of parts whose text parts carry text. */
    private static void content(ObjectNode message, UnaryOperator<String> f) {
        JsonNode content = message.path("content");
        if (content.isString()) {
            message.put("content", f.apply(content.asString()));
        } else if (content instanceof ArrayNode parts) {
            for (JsonNode part : parts) {
                if (part instanceof ObjectNode p && "text".equals(p.path("type").asString(""))) {
                    p.put("text", f.apply(p.path("text").asString("")));
                }
            }
        }
    }

    /** An assistant's tool calls: the arguments are a JSON string; ids and function names are left alone. */
    private static void toolCalls(ObjectNode message, UnaryOperator<String> f) {
        for (JsonNode call : message.path("tool_calls")) {
            if (call.path("function") instanceof ObjectNode fn && fn.path("arguments").isString()) {
                fn.put("arguments", f.apply(fn.path("arguments").asString()));
            }
        }
    }

    @Override
    public String error(ObjectMapper json, Refusal kind, String message) {
        String type;
        String code;
        switch (kind) {
            case AUTH -> { type = "invalid_request_error"; code = "invalid_api_key"; }
            // the SDK reads a 402 / insufficient_quota as a wall, not something to retry
            case BILLING -> { type = "insufficient_quota"; code = "insufficient_quota"; }
            case NOT_FOUND -> { type = "invalid_request_error"; code = "not_found"; }
            case UPSTREAM, UNAVAILABLE -> { type = "server_error"; code = null; }
            default -> { type = "invalid_request_error"; code = null; }
        }
        ObjectNode error = json.createObjectNode().put("message", message).put("type", type);
        if (code == null) {
            error.putNull("code");
        } else {
            error.put("code", code);
        }
        return json.createObjectNode().set("error", error).toString();
    }
}
