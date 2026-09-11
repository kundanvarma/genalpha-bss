package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.ExplainService;
import com.bss.ontology.service.UpgradeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Ontology MCP: a Model Context Protocol server (JSON-RPC 2.0 over HTTP)
 * whose tools are GENERATED from the registry. An agent does not discover
 * "POST productOrder"; it discovers upgrade_subscription with the action's
 * meaning, preconditions and permissions as its description — and calls it
 * with the delegated token of the person it acts for. Writes go through
 * actions only; reads are the registry and the derived views.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/mcp")
public class McpController {

    private static final String PROTOCOL = "2025-06-18";

    private final Registry registry;
    private final ActionCheckService checks;
    private final ActionExecuteService executes;
    private final UpgradeService upgrades;
    private final ExplainService explain;
    private final TenantScope tenantScope;
    private final ObjectMapper json;
    private final com.bss.ontology.service.ContextService context;
    private final com.bss.ontology.service.RecommendationService recommendations;

    public McpController(Registry registry, ActionCheckService checks, ActionExecuteService executes,
            UpgradeService upgrades, ExplainService explain, TenantScope tenantScope, ObjectMapper json,
            com.bss.ontology.service.ContextService context, com.bss.ontology.service.RecommendationService recommendations) {
        this.recommendations = recommendations;
        this.context = context;
        this.registry = registry;
        this.checks = checks;
        this.executes = executes;
        this.upgrades = upgrades;
        this.explain = explain;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    /** A plain description for people who open the URL. */
    @GetMapping
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "genalpha-ontology");
        out.put("protocol", PROTOCOL);
        out.put("transport", "JSON-RPC 2.0 over HTTP POST, bearer token of the person the agent acts for");
        out.put("tools", tools().stream().map(t -> t.get("name")).toList());
        return out;
    }

    @PostMapping
    public ResponseEntity<Object> rpc(@RequestBody JsonNode body, HttpServletRequest request) {
        if (body.isArray()) {
            List<Object> replies = new ArrayList<>();
            for (JsonNode one : body) {
                Object r = handle(one, request);
                if (r != null) {
                    replies.add(r);
                }
            }
            return ResponseEntity.ok(replies);
        }
        Object r = handle(body, request);
        return r == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(r);
    }

    private Object handle(JsonNode req, HttpServletRequest request) {
        JsonNode id = req.get("id");
        String method = req.path("method").asText();
        JsonNode params = req.path("params");
        if (method.startsWith("notifications/")) {
            return null;
        }
        try {
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", PROTOCOL, "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "genalpha-ontology", "version", "1.0.0"),
                        "instructions", "Every tool is a governed business action or a read of the ontology. Writes are refused with the failed condition named; read it back to the person.");
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(params, request);
                case "resources/list" -> Map.of("resources", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "method not found: " + method);
            }
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("jsonrpc", "2.0");
            ok.put("id", id);
            ok.put("result", result);
            return ok;
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return error(id, -32603, e.getMessage());
        }
    }

    private static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("jsonrpc", "2.0");
        err.put("id", id);
        err.put("error", Map.of("code", code, "message", message));
        return err;
    }

    /* ------------------------------------------------------------------ tools, generated */

    List<Map<String, Object>> tools() {
        Registry.Layer l = registry.forTenant(tenantScope.currentTenantId());
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("list_actions", "List every governed business action this BSS offers, with its meaning and who may perform it.", Map.of()));
        tools.add(tool("explain", "Explain a concept, an action, a console page or a whole journey from the operational ontology, in words.",
                Map.of("kind", Map.of("type", "string", "enum", List.of("concept", "action", "page", "journey")), "name", Map.of("type", "string")),
                List.of("kind", "name")));
        tools.add(tool("available_upgrades", "The offerings a subscription could move up to: same family, on sale on your channel, dearer per month.",
                Map.of("subscriptionId", Map.of("type", "string", "description", "the subscription (product) id")), List.of("subscriptionId")));
        tools.add(tool("recommend", "What should be done next for a customer: governed actions dry-run through the registry (with every condition's verdict) and things to explain — an open incident, a paused line, an open bill, a dearer plan. Grounded; no free text.",
                Map.of("customerId", Map.of("type", "string", "description", "the customer (party) id")), List.of("customerId")));
        tools.add(tool("customer_context", "One call: a customer's subscriptions (with what each could become), lines, bills and the receipts of what the BSS decided about them — walked with your rights; edges that did not answer are listed.",
                Map.of("customerId", Map.of("type", "string", "description", "the customer (party) id")), List.of("customerId")));
        for (JsonNode a : l.actions().values()) {
            if ("deprecated".equals(a.path("status").asText())) {
                continue;
            }
            String snake = snake(a.path("action").asText());
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (JsonNode in : a.path("inputs")) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", switch (in.path("type").asText()) { case "number", "money" -> "number"; case "boolean" -> "boolean"; default -> "string"; });
                p.put("description", in.path("meaning").asText(in.has("concept") ? "id of a " + in.path("concept").asText() : in.path("name").asText()));
                props.put(in.path("name").asText(), p);
                if (in.path("required").asBoolean(false)) {
                    required.add(in.path("name").asText());
                }
            }
            String description = a.path("meaning").asText() + " Who may: " + ExplainService.who(a) + ".";
            tools.add(tool("check_" + snake, "Dry run of " + snake + ": may it happen for these inputs, and if not, which condition fails? " + description, props, required));
            tools.add(tool(snake, description + " Executes through " + a.path("executes").path("capability").asText()
                    + " with the caller's own rights after preconditions, permission and policy; writes a decision receipt.", props, required));
        }
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props) {
        return tool(name, description, props, List.of());
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    private Map<String, Object> call(JsonNode params, HttpServletRequest request) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        String tenant = tenantScope.currentTenantId();
        Caller caller = Caller.current(request, tenant);
        Registry.Layer l = registry.forTenant(tenant);
        Object result;
        boolean isError = false;
        if ("list_actions".equals(name)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode a : l.actions().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("action", a.path("action").asText());
                row.put("tool", snake(a.path("action").asText()));
                row.put("concept", a.path("concept").asText());
                row.put("meaning", a.path("meaning").asText());
                row.put("whoMay", ExplainService.who(a));
                row.put("version", a.path("version").asInt());
                row.put("status", a.path("status").asText());
                rows.add(row);
            }
            result = rows;
        } else if ("explain".equals(name)) {
            String kind = args.path("kind").asText();
            String what = args.path("name").asText();
            result = switch (kind) {
                case "concept" -> explain.concept(what, tenant);
                case "action" -> explain.action(what, tenant);
                case "journey" -> explain.journey(what, tenant);
                case "page" -> explain.page(what, tenant);
                default -> null;
            };
            if (result == null) {
                result = Map.of("error", "nothing named \"" + what + "\" of kind " + kind);
                isError = true;
            }
        } else if ("available_upgrades".equals(name)) {
            result = upgrades.availableUpgrades(args.path("subscriptionId").asText(), caller);
        } else if ("customer_context".equals(name)) {
            result = context.customer(args.path("customerId").asText(), caller);
        } else if ("recommend".equals(name)) {
            result = recommendations.forCustomer(args.path("customerId").asText(), caller);
        } else {
            boolean dry = name.startsWith("check_");
            String actionName = camel(dry ? name.substring(6) : name);
            JsonNode action = l.actions().get(actionName);
            if (action == null) {
                throw new IllegalArgumentException("unknown tool: " + name);
            }
            Map<String, String> inputs = new LinkedHashMap<>();
            args.fields().forEachRemaining(f -> inputs.put(f.getKey(), f.getValue().asText()));
            if (dry) {
                ActionCheckService.Check c = checks.check(action, inputs, caller);
                result = c.toMap();
                isError = !c.allowed();
            } else {
                ActionExecuteService.Outcome o = executes.execute(action, inputs, caller);
                result = o.body();
                isError = !o.done();
            }
        }
        String text;
        try {
            text = json.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            text = String.valueOf(result);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", text)));
        out.put("structuredContent", result instanceof Map || result instanceof List ? result : Map.of("value", result));
        out.put("isError", isError);
        return out;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
