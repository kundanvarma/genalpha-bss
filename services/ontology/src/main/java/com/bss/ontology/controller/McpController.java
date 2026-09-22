package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.McpMessages;
import com.bss.ontology.dto.McpMessages.ActionRow;
import com.bss.ontology.dto.McpMessages.AgentRow;
import com.bss.ontology.dto.McpMessages.InputSchema;
import com.bss.ontology.dto.McpMessages.Property;
import com.bss.ontology.dto.McpMessages.Tool;
import com.bss.ontology.dto.McpMessages.ToolResult;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.ContextService;
import com.bss.ontology.service.ExplainService;
import com.bss.ontology.service.RecommendationService;
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
    private final ContextService context;
    private final RecommendationService recommendations;

    public McpController(Registry registry, ActionCheckService checks, ActionExecuteService executes,
            UpgradeService upgrades, ExplainService explain, TenantScope tenantScope, ObjectMapper json,
            ContextService context, RecommendationService recommendations) {
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
    public McpMessages.Description describe() {
        return new McpMessages.Description("genalpha-ontology", PROTOCOL,
                "JSON-RPC 2.0 over HTTP POST, bearer token of the person the agent acts for",
                tools().stream().map(Tool::name).toList());
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
                case "initialize" -> new McpMessages.Initialize(PROTOCOL,
                        new McpMessages.Initialize.Capabilities(new McpMessages.Initialize.Tools(false)),
                        new McpMessages.Initialize.ServerInfo("genalpha-ontology", "1.0.0"),
                        "Every tool is a governed business action or a read of the ontology. Writes are refused with the failed condition named; read it back to the person.");
                case "ping" -> Map.of();
                case "tools/list" -> new McpMessages.ToolList(tools());
                case "tools/call" -> call(params, request);
                case "resources/list" -> Map.of("resources", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "method not found: " + method);
            }
            return new McpMessages.Reply("2.0", id, result);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return error(id, -32603, e.getMessage());
        }
    }

    private static McpMessages.ErrorReply error(JsonNode id, int code, String message) {
        return new McpMessages.ErrorReply("2.0", id, new McpMessages.ErrorReply.Error(code, message));
    }

    /* ------------------------------------------------------------------ tools, generated */

    List<Tool> tools() {
        Registry.Layer l = registry.forTenant(tenantScope.currentTenantId());
        List<Tool> tools = new ArrayList<>();
        tools.add(tool("list_actions", "List every governed business action this BSS offers, with its meaning and who may perform it.", new LinkedHashMap<>()));
        Map<String, Property> explainProps = new LinkedHashMap<>();
        explainProps.put("kind", new Property("string", null, List.of("concept", "action", "page", "journey")));
        explainProps.put("name", new Property("string", null, null));
        tools.add(tool("explain", "Explain a concept, an action, a console page or a whole journey from the operational ontology, in words.",
                explainProps, List.of("kind", "name")));
        tools.add(tool("available_upgrades", "The offerings a subscription could move up to: same family, on sale on your channel, dearer per month.",
                one("subscriptionId", "the subscription (product) id"), List.of("subscriptionId")));
        tools.add(tool("recommend", "What should be done next for a customer: governed actions dry-run through the registry (with every condition's verdict) and things to explain — an open incident, a paused line, an open bill, a dearer plan. Grounded; no free text.",
                one("customerId", "the customer (party) id"), List.of("customerId")));
        tools.add(tool("list_agents", "The registered AI agents of this BSS: whose rights each runs with, what it may read, check and execute, and how autonomous it is.", new LinkedHashMap<>()));
        tools.add(tool("customer_context", "One call: a customer's subscriptions (with what each could become), lines, bills and the receipts of what the BSS decided about them — walked with your rights; edges that did not answer are listed.",
                one("customerId", "the customer (party) id"), List.of("customerId")));
        for (JsonNode a : l.actions().values()) {
            if ("deprecated".equals(a.path("status").asText())) {
                continue;
            }
            String snake = snake(a.path("action").asText());
            Map<String, Property> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (JsonNode in : a.path("inputs")) {
                String type = switch (in.path("type").asText()) { case "number", "money" -> "number"; case "boolean" -> "boolean"; default -> "string"; };
                props.put(in.path("name").asText(), new Property(type,
                        in.path("meaning").asText(in.has("concept") ? "id of a " + in.path("concept").asText() : in.path("name").asText()), null));
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

    private static Map<String, Property> one(String name, String description) {
        Map<String, Property> props = new LinkedHashMap<>();
        props.put(name, Property.string(description));
        return props;
    }

    private static Tool tool(String name, String description, Map<String, Property> props) {
        return tool(name, description, props, List.of());
    }

    private static Tool tool(String name, String description, Map<String, Property> props, List<String> required) {
        return new Tool(name, description, new InputSchema("object", props, required.isEmpty() ? null : required));
    }

    private ToolResult call(JsonNode params, HttpServletRequest request) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        String tenant = tenantScope.currentTenantId();
        Caller caller = Caller.current(request, tenant);
        if (caller.agent() == null) {
            caller = caller.withAgent("external-mcp");
        }
        Registry.Layer l = registry.forTenant(tenant);
        Object result;
        boolean isError = false;
        if ("list_actions".equals(name)) {
            List<ActionRow> rows = new ArrayList<>();
            for (JsonNode a : l.actions().values()) {
                rows.add(new ActionRow(a.path("action").asText(), snake(a.path("action").asText()), a.path("concept").asText(),
                        a.path("meaning").asText(), ExplainService.who(a), a.path("version").asInt(), a.path("status").asText()));
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
                result = new McpMessages.NotFound("nothing named \"" + what + "\" of kind " + kind);
                isError = true;
            }
        } else if ("available_upgrades".equals(name)) {
            result = upgrades.availableUpgrades(args.path("subscriptionId").asText(), caller);
        } else if ("list_agents".equals(name)) {
            List<AgentRow> rows = new ArrayList<>();
            for (JsonNode a : l.agents().values()) {
                rows.add(new AgentRow(a.path("agent").asText(), a.path("kind").asText(), a.path("meaning").asText(),
                        a.path("runsAs").asText(), a.path("autonomy").asText(), a.path("actions").path("execute")));
            }
            result = rows;
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
                Check c = checks.check(action, inputs, caller);
                result = c;
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
        return new ToolResult(List.of(new ToolResult.Content("text", text)), McpMessages.structured(result), isError);
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
