package com.bss.intelligence.door;

import com.bss.intelligence.service.Redaction;
import com.bss.intelligence.service.Redactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Redaction at the model door (#283, #308), for the Anthropic Messages shape.
 *
 * OUT, to the provider: every piece of TEXT a worker's call carries — the system
 * prompt, message text, tool results (where customer data actually arrives, from
 * the BSS's own tools), and the arguments of earlier tool calls in the history —
 * goes through the shared redactor, one map per call. Never touched: ids
 * (tool_use ids, the model name), the tool definitions, and THINKING blocks,
 * which the provider signs and refuses to accept back altered.
 *
 * BACK, to the worker: the placeholders are reversed in the answer's text AND
 * inside its tool calls' arguments, so when the model says "look up <email#1>"
 * the worker's tool runs with the real address. The worker therefore only ever
 * holds real values, its history carries real values, and the next call is
 * redacted from scratch — one map per call stays consistent across a whole shift.
 */
final class DoorRedaction {

    private DoorRedaction() {
    }

    /** Redact a Messages request in place. */
    static void redactRequest(JsonNode root, Redactor redactor, Redaction map) {
        if (!(root instanceof ObjectNode request)) {
            return;
        }
        UnaryOperator<String> redact = text -> redactor.redact(text, map);
        JsonNode system = request.path("system");
        if (system.isString()) {
            request.put("system", redact.apply(system.asString()));
        } else if (system instanceof ArrayNode blocks) {
            textBlocks(blocks, redact);
        }
        for (JsonNode message : request.path("messages")) {
            if (!(message instanceof ObjectNode m)) {
                continue;
            }
            JsonNode content = m.path("content");
            if (content.isString()) {
                m.put("content", redact.apply(content.asString()));
                continue;
            }
            if (!(content instanceof ArrayNode blocks)) {
                continue;
            }
            for (JsonNode block : blocks) {
                if (!(block instanceof ObjectNode b)) {
                    continue;
                }
                switch (b.path("type").asString("")) {
                    case "text" -> b.put("text", redact.apply(b.path("text").asString("")));
                    case "tool_result" -> {
                        JsonNode result = b.path("content");
                        if (result.isString()) {
                            b.put("content", redact.apply(result.asString()));
                        } else if (result instanceof ArrayNode resultBlocks) {
                            textBlocks(resultBlocks, redact);
                        }
                    }
                    case "tool_use" -> strings(b.path("input"), redact);
                    default -> { } // thinking (signed), images, documents: left exactly as sent
                }
            }
        }
    }

    /** Reverse the placeholders in a Messages answer in place: its text, and its tool calls' arguments. */
    static void restoreResponse(JsonNode root, Redaction map) {
        UnaryOperator<String> restore = map::restore;
        for (JsonNode block : root.path("content")) {
            if (!(block instanceof ObjectNode b)) {
                continue;
            }
            switch (b.path("type").asString("")) {
                case "text" -> b.put("text", restore.apply(b.path("text").asString("")));
                case "tool_use" -> strings(b.path("input"), restore);
                default -> { } // thinking stays signed and untouched
            }
        }
    }

    private static void textBlocks(ArrayNode blocks, UnaryOperator<String> f) {
        for (JsonNode block : blocks) {
            if (block instanceof ObjectNode b && "text".equals(b.path("type").asString(""))) {
                b.put("text", f.apply(b.path("text").asString("")));
            }
        }
    }

    /** Apply f to every string VALUE under a node (never to a property name). */
    private static void strings(JsonNode node, UnaryOperator<String> f) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : object.properties()) {
                names.add(e.getKey());
            }
            for (String name : names) {
                JsonNode value = object.get(name);
                if (value.isString()) {
                    object.put(name, f.apply(value.asString()));
                } else {
                    strings(value, f);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isString()) {
                    array.set(i, JsonNodeFactory.instance.stringNode(f.apply(value.asString())));
                } else {
                    strings(value, f);
                }
            }
        }
    }
}
