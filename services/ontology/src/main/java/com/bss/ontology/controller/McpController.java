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
import com.bss.ontology.service.McpApps;
import com.bss.ontology.service.McpResources;
import com.bss.ontology.service.RecommendationService;
import com.bss.ontology.service.UpgradeService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
 *
 * Dual-era (MCP 2026-07-28): a modern client sends every request on its own,
 * version and capabilities in {@code _meta}, mirrored in headers a gateway can
 * route on — no session, no handshake; a 2025-06-18 client still opens with
 * {@code initialize}. {@link McpEnvelope} holds the envelope rules. Resources are
 * the ontology's concepts and the MCP Apps cards ({@link McpResources}, {@link McpApps}).
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/mcp")
public class McpController {

    private static final McpMessages.ServerInfo SERVER = new McpMessages.ServerInfo("genalpha-ontology", "1.1.0");
    private static final String INSTRUCTIONS = "Every tool is a governed business action or a read of the ontology. "
            + "Writes are refused with the failed condition named; read it back to the person. "
            + "The resources are the ontology's concepts: read one to learn what a thing is before acting on it.";
    private static final McpMessages.Capabilities CAPABILITIES = new McpMessages.Capabilities(
            new McpMessages.Capabilities.Tools(false), new McpMessages.Capabilities.Resources(false, false),
            new McpMessages.Capabilities.Prompts(false),
            Map.of("io.modelcontextprotocol/ui", new McpMessages.Capabilities.AppsExtension(List.of(McpApps.MIME))));

    private final Registry registry;
    private final ActionCheckService checks;
    private final ActionExecuteService executes;
    private final UpgradeService upgrades;
    private final ExplainService explain;
    private final TenantScope tenantScope;
    private final ObjectMapper json;
    private final ContextService context;
    private final RecommendationService recommendations;
    private final McpResources resources;
    private final McpApps apps;

    public McpController(Registry registry, ActionCheckService checks, ActionExecuteService executes,
            UpgradeService upgrades, ExplainService explain, TenantScope tenantScope, ObjectMapper json,
            ContextService context, RecommendationService recommendations, McpResources resources, McpApps apps) {
        this.resources = resources;
        this.apps = apps;
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
        return new McpMessages.Description(SERVER.name(), String.join(", ", McpEnvelope.SUPPORTED),
                "JSON-RPC 2.0 over HTTP POST, bearer token of the person the agent acts for",
                tools().stream().map(Tool::name).toList());
    }

    @PostMapping
    public ResponseEntity<Object> rpc(@RequestBody JsonNode body, HttpServletRequest request) {
        if (body.isArray()) {
            // a legacy batch: each member answered on its own; the HTTP status says only that the batch was read
            List<Object> replies = new ArrayList<>();
            for (JsonNode one : body) {
                Answer a = handle(one, request);
                if (a.body() != null) {
                    replies.add(a.body());
                }
            }
            return ResponseEntity.ok(replies);
        }
        Answer a = handle(body, request);
        return a.body() == null ? ResponseEntity.accepted().build() : ResponseEntity.status(a.status()).body(a.body());
    }

    /** One JSON-RPC message answered: the HTTP status the transport requires, and the reply (null for a notification). */
    private record Answer(int status, Object body) {
    }

    private Answer handle(JsonNode req, HttpServletRequest request) {
        JsonNode id = req.get("id");
        String method = req.path("method").asString("");
        JsonNode params = req.path("params");
        McpEnvelope.Era era;
        try {
            era = McpEnvelope.validate(req, request);
        } catch (McpEnvelope.Refusal r) {
            return new Answer(r.status, error(id, r.code, r.getMessage(), r.data));
        }
        if (method.startsWith("notifications/")) {
            return new Answer(202, null);
        }
        try {
            Object result = switch (method) {
                case "initialize" -> new McpMessages.Initialize(
                        McpEnvelope.negotiate(params.path("protocolVersion").isString() ? params.path("protocolVersion").asString() : null),
                        CAPABILITIES, SERVER, INSTRUCTIONS);
                case "server/discover" -> new McpMessages.Discover(McpEnvelope.SUPPORTED, CAPABILITIES, INSTRUCTIONS);
                case "ping" -> Map.of();
                case "tools/list" -> new McpMessages.ToolList(tools());
                case "tools/call" -> call(params, request);
                case "resources/list" -> resources.list(tenantScope.currentTenantId());
                case "resources/read" -> resources.read(params.path("uri").asString(""), tenantScope.currentTenantId());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> null;
            };
            if (result == null) {
                // a modern client tells "no such method" from "no MCP here" by the 404 together with the JSON-RPC body
                return new Answer(era.modern() ? 404 : 200, error(id, -32601, "method not found: " + method, null));
            }
            return new Answer(200, new McpMessages.Reply("2.0", id, era.modern() ? modern(result) : result));
        } catch (IllegalArgumentException e) {
            return new Answer(200, error(id, McpEnvelope.INVALID_PARAMS, e.getMessage(), null));
        } catch (RuntimeException e) {
            // An internal fault's own text (SQL, a JDBC URL, an issuer) is for the operator,
            // not the client (review finding 24): the client gets a reference it can quote,
            // the log gets the fault under the same reference. Never the params — they can
            // carry a person's words or a prompt.
            String reference = java.util.UUID.randomUUID().toString();
            log.error("mcp {} failed, reference {}", oneLine(method), reference, e);
            return new Answer(200, error(id, -32603, "internal error — quote reference " + reference
                    + " to the operator", new McpMessages.InternalErrorData(reference)));
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpController.class);

    /** A caller-chosen method name reaches the log on one line. */
    private static String oneLine(String value) {
        return value == null ? null : value
                .replace('\n', '_').replace('\r', '_')
                .replace('\u0085', '_').replace(' ', '_').replace(' ', '_');
    }

    /**
     * A 2026-07-28 result says what kind of result it is ({@code resultType}) and who
     * answered ({@code _meta} serverInfo), because no handshake told the client either.
     * A legacy client gets its result exactly as before.
     */
    private JsonNode modern(Object result) {
        JsonNode tree = json.valueToTree(result);
        ObjectNode node = tree instanceof ObjectNode o ? o : json.createObjectNode();
        node.put("resultType", "complete");
        ObjectNode meta = node.get("_meta") instanceof ObjectNode m ? m : node.putObject("_meta");
        meta.set(McpEnvelope.META_SERVER_INFO, json.valueToTree(SERVER));
        return node;
    }

    private static McpMessages.ErrorReply error(JsonNode id, int code, String message, Object data) {
        return new McpMessages.ErrorReply("2.0", id, new McpMessages.ErrorReply.Error(code, message, data));
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
            if ("deprecated".equals(a.path("status").asString())) {
                continue;
            }
            String snake = snake(a.path("action").asString());
            Map<String, Property> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (JsonNode in : a.path("inputs")) {
                String type = switch (in.path("type").asString()) { case "number", "money" -> "number"; case "boolean" -> "boolean"; default -> "string"; };
                props.put(in.path("name").asString(), new Property(type,
                        in.path("meaning").asString(in.has("concept") ? "id of a " + in.path("concept").asString() : in.path("name").asString()), null));
                if (in.path("required").asBoolean(false)) {
                    required.add(in.path("name").asString());
                }
            }
            String description = a.path("meaning").asString() + " Who may: " + ExplainService.who(a) + ".";
            String check = "check_" + snake;
            tools.add(new Tool(check, "Dry run of " + snake + ": may it happen for these inputs, and if not, which condition fails? " + description,
                    new InputSchema("object", props, required.isEmpty() ? null : required), apps.metaFor(check)));
            tools.add(tool(snake, description + " Executes through " + a.path("executes").path("capability").asString()
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
        String name = params.path("name").asString();
        JsonNode args = params.path("arguments");
        String tenant = tenantScope.currentTenantId();
        Caller caller = Caller.current(request, tenant);
        if (caller.agent() == null) {
            caller = caller.withAgent("external-mcp");
        }
        Registry.Layer l = registry.forTenant(tenant);
        Object result;
        boolean isError = false;
        McpMessages.ToolResultMeta card = null;
        if ("list_actions".equals(name)) {
            List<ActionRow> rows = new ArrayList<>();
            for (JsonNode a : l.actions().values()) {
                rows.add(new ActionRow(a.path("action").asString(), snake(a.path("action").asString()), a.path("concept").asString(),
                        a.path("meaning").asString(), ExplainService.who(a), a.path("version").asInt(), a.path("status").asString()));
            }
            result = rows;
        } else if ("explain".equals(name)) {
            String kind = args.path("kind").asString();
            String what = args.path("name").asString();
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
            result = upgrades.availableUpgrades(args.path("subscriptionId").asString(), caller);
        } else if ("list_agents".equals(name)) {
            List<AgentRow> rows = new ArrayList<>();
            for (JsonNode a : l.agents().values()) {
                rows.add(new AgentRow(a.path("agent").asString(), a.path("kind").asString(), a.path("meaning").asString(),
                        a.path("runsAs").asString(), a.path("autonomy").asString(), a.path("actions").path("execute")));
            }
            result = rows;
        } else if ("customer_context".equals(name)) {
            result = context.customer(args.path("customerId").asString(), caller);
        } else if ("recommend".equals(name)) {
            result = recommendations.forCustomer(args.path("customerId").asString(), caller);
        } else {
            boolean dry = name.startsWith("check_");
            String actionName = camel(dry ? name.substring(6) : name);
            JsonNode action = l.actions().get(actionName);
            if (action == null) {
                throw new IllegalArgumentException("unknown tool: " + name);
            }
            Map<String, String> inputs = new LinkedHashMap<>();
            args.properties().forEach(f -> inputs.put(f.getKey(), f.getValue().asString()));
            if (dry) {
                Check c = checks.check(action, inputs, caller);
                result = c;
                isError = !c.allowed();
                card = apps.cardFor(name, inputs, c);
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
        return new ToolResult(List.of(new ToolResult.Content("text", text)), McpMessages.structured(result), isError, card);
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
