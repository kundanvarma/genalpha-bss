package com.bss.ontology.registry;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enforces the registry's own schemas (ontology/schema/*.schema.json) on every
 * loaded document: required keys, closed objects, enums, patterns, types, item
 * shapes, minItems/minLength/minimum. The schemas stay the single source; this
 * class only knows the JSON Schema keywords those files use, so a definition
 * that drifts from its shape fails at startup and in CI — never at runtime.
 */
public final class SchemaCheck {

    private SchemaCheck() {
    }

    public static List<String> validate(JsonNode schema, JsonNode doc, String where) {
        List<String> errors = new ArrayList<>();
        check(schema, doc, where, errors);
        return errors;
    }

    private static void check(JsonNode schema, JsonNode node, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode()) {
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null) {
            boolean ok = false;
            for (JsonNode v : enumNode) {
                if (v.equals(node)) {
                    ok = true;
                }
            }
            if (!ok) {
                errors.add(path + ": must be one of " + enumNode + ", was " + node);
                return;
            }
        }
        JsonNode type = schema.get("type");
        if (type != null) {
            String t = type.asString();
            boolean ok = switch (t) {
                case "object" -> node.isObject();
                case "array" -> node.isArray();
                case "string" -> node.isTextual();
                case "integer" -> node.isIntegralNumber();
                case "number" -> node.isNumber();
                case "boolean" -> node.isBoolean();
                default -> true;
            };
            if (!ok) {
                errors.add(path + ": expected " + t + ", was " + kind(node));
                return;
            }
        }
        if (node.isTextual()) {
            JsonNode pattern = schema.get("pattern");
            if (pattern != null && !Pattern.compile(pattern.asString()).matcher(node.asString()).find()) {
                errors.add(path + ": \"" + node.asString() + "\" does not match " + pattern.asString());
            }
            JsonNode minLength = schema.get("minLength");
            if (minLength != null && node.asString().length() < minLength.asInt()) {
                errors.add(path + ": shorter than " + minLength.asInt() + " characters");
            }
            if ("date".equals(text(schema, "format")) && !Pattern.matches("\\d{4}-\\d{2}-\\d{2}", node.asString())) {
                errors.add(path + ": not a date (YYYY-MM-DD)");
            }
        }
        if (node.isNumber()) {
            JsonNode minimum = schema.get("minimum");
            if (minimum != null && node.asDouble() < minimum.asDouble()) {
                errors.add(path + ": below minimum " + minimum);
            }
        }
        if (node.isArray()) {
            JsonNode minItems = schema.get("minItems");
            if (minItems != null && node.size() < minItems.asInt()) {
                errors.add(path + ": needs at least " + minItems.asInt() + " items");
            }
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < node.size(); i++) {
                    check(items, node.get(i), path + "[" + i + "]", errors);
                }
            }
        }
        if (node.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null) {
                for (JsonNode r : required) {
                    if (!node.has(r.asString())) {
                        errors.add(path + ": missing required \"" + r.asString() + "\"");
                    }
                }
            }
            JsonNode props = schema.get("properties");
            JsonNode additional = schema.get("additionalProperties");
            boolean closed = additional != null && additional.isBoolean() && !additional.asBoolean();
            for (Map.Entry<String, JsonNode> en : node.properties()) {
                JsonNode sub = props == null ? null : props.get(en.getKey());
                if (sub != null) {
                    check(sub, en.getValue(), path + "." + en.getKey(), errors);
                } else if (closed) {
                    errors.add(path + ": unknown key \"" + en.getKey() + "\"");
                } else if (additional != null && additional.isObject()) {
                    check(additional, en.getValue(), path + "." + en.getKey(), errors);
                }
            }
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null ? null : v.asString();
    }

    private static String kind(JsonNode n) {
        if (n == null || n.isNull()) {
            return "null";
        }
        return n.getNodeType().name().toLowerCase();
    }
}
