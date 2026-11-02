package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The Model Context Protocol's own shapes as this server speaks them: the
 * JSON-RPC envelope, the tool list (generated from the registry) with each
 * tool's input schema, and a tool call's result. {@code id} is the client's
 * and may be anything, so it stays a node.
 */
public final class McpMessages {

    private McpMessages() {
    }

    /** A plain description for people who open the URL. */
    @JsonPropertyOrder({"name", "protocol", "transport", "tools"})
    public record Description(String name, String protocol, String transport, List<String> tools) {
    }

    @JsonPropertyOrder({"jsonrpc", "id", "result"})
    public record Reply(String jsonrpc, JsonNode id, Object result) {
    }

    @JsonPropertyOrder({"jsonrpc", "id", "error"})
    public record ErrorReply(String jsonrpc, JsonNode id, Error error) {
        @JsonPropertyOrder({"code", "message", "data"})
        public record Error(int code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) Object data) {
            public Error(int code, String message) {
                this(code, message, null);
            }
        }
    }

    /** {@code data} of an UnsupportedProtocolVersion error (-32022, MCP 2026-07-28): what we speak, and what was asked. */
    @JsonPropertyOrder({"supported", "requested"})
    public record UnsupportedVersion(List<String> supported, String requested) {
    }

    /**
     * What this server offers. {@code extensions} names the MCP Apps extension
     * ({@code io.modelcontextprotocol/ui}): it serves {@code ui://} resources.
     */
    @JsonPropertyOrder({"tools", "resources", "prompts", "extensions"})
    public record Capabilities(Tools tools, Resources resources, Prompts prompts, Map<String, AppsExtension> extensions) {
        public record Tools(boolean listChanged) {
        }

        @JsonPropertyOrder({"subscribe", "listChanged"})
        public record Resources(boolean subscribe, boolean listChanged) {
        }

        public record Prompts(boolean listChanged) {
        }

        public record AppsExtension(List<String> mimeTypes) {
        }
    }

    @JsonPropertyOrder({"name", "version"})
    public record ServerInfo(String name, String version) {
    }

    /** The answer to a legacy (2025-06-18) {@code initialize}: the negotiated version and what we offer. */
    @JsonPropertyOrder({"protocolVersion", "capabilities", "serverInfo", "instructions"})
    public record Initialize(String protocolVersion, Capabilities capabilities, ServerInfo serverInfo, String instructions) {
    }

    /** The answer to {@code server/discover} (2026-07-28): every version we speak, what we offer, who we are. */
    @JsonPropertyOrder({"supportedVersions", "capabilities", "instructions"})
    public record Discover(List<String> supportedVersions, Capabilities capabilities, String instructions) {
    }

    public record ToolList(List<Tool> tools) {
    }

    /** One generated tool: its name, the action's meaning as its description, a JSON schema of the action's inputs,
     *  and — for a tool with a card — the MCP Apps link to its {@code ui://} resource. */
    @JsonPropertyOrder({"name", "description", "inputSchema", "_meta"})
    public record Tool(String name, String description, InputSchema inputSchema,
            @JsonProperty("_meta") @JsonInclude(JsonInclude.Include.NON_NULL) ToolMeta meta) {
        public Tool(String name, String description, InputSchema inputSchema) {
            this(name, description, inputSchema, null);
        }
    }

    /** {@code _meta} of a tool: {@code ui.resourceUri} per SEP-1865 (MCP Apps). */
    public record ToolMeta(ToolUi ui) {
    }

    /** {@code visibility}: who may call the tool — the model, the app (the card), or both. */
    @JsonPropertyOrder({"resourceUri", "visibility"})
    public record ToolUi(String resourceUri, List<String> visibility) {
    }

    /** One readable resource: a concept of the ontology ({@code ontology://concept/<Name>}) or a card ({@code ui://}). */
    @JsonPropertyOrder({"uri", "name", "title", "description", "mimeType", "_meta"})
    public record Resource(String uri, String name, String title, String description, String mimeType,
            @JsonProperty("_meta") @JsonInclude(JsonInclude.Include.NON_NULL) ResourceMeta meta) {
    }

    public record ResourceList(List<Resource> resources) {
    }

    /** {@code resources/read}: the contents of one resource, as text. */
    public record ResourceRead(List<ResourceContent> contents) {
    }

    @JsonPropertyOrder({"uri", "mimeType", "text", "_meta"})
    public record ResourceContent(String uri, String mimeType, String text,
            @JsonProperty("_meta") @JsonInclude(JsonInclude.Include.NON_NULL) ResourceMeta meta) {
    }

    /** {@code _meta.ui} of a UI resource (SEP-1865): its content policy and whether the host should frame it. */
    public record ResourceMeta(UiResource ui) {
    }

    @JsonPropertyOrder({"csp", "prefersBorder"})
    public record UiResource(Csp csp, boolean prefersBorder) {
    }

    /** No outside origin at all: the card is self-contained and talks only to its host. */
    @JsonPropertyOrder({"connectDomains", "resourceDomains"})
    public record Csp(List<String> connectDomains, List<String> resourceDomains) {
    }

    /**
     * What the pick-benefit card needs to ask a person: the benefit by name, its cost
     * in Choices, the dry run's verdict, and the exact call Confirm makes. Built by the
     * server from what it resolved, so the card shows facts and decides nothing.
     */
    @JsonPropertyOrder({"action", "confirmTool", "arguments", "benefitName", "choiceCost", "allowed", "refusal"})
    public record ConfirmCard(String action, String confirmTool, Map<String, String> arguments, String benefitName,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer choiceCost, boolean allowed,
            @JsonInclude(JsonInclude.Include.NON_NULL) String refusal) {
    }

    /** {@code _meta} of a tool result that carries a card; the key is ours (reverse-DNS, not MCP-reserved). */
    public record ToolResultMeta(@JsonProperty(CONFIRM_CARD_KEY) ConfirmCard confirmCard) {
    }

    public static final String CONFIRM_CARD_KEY = "bss.genalpha/confirmCard";

    @JsonPropertyOrder({"type", "properties", "required"})
    public record InputSchema(String type, Map<String, Property> properties, @JsonInclude(JsonInclude.Include.NON_NULL) List<String> required) {
    }

    @JsonPropertyOrder({"type", "description", "enum"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Property(String type, String description, @JsonProperty("enum") List<String> allowed) {
        public static Property string(String description) {
            return new Property("string", description, null);
        }
    }

    /** What a tool call returned: the result as text for the model, structured for the client, and whether it was a refusal. */
    @JsonPropertyOrder({"content", "structuredContent", "isError", "_meta"})
    public record ToolResult(List<Content> content, Object structuredContent, boolean isError,
            @JsonProperty("_meta") @JsonInclude(JsonInclude.Include.NON_NULL) ToolResultMeta meta) {
        public ToolResult(List<Content> content, Object structuredContent, boolean isError) {
            this(content, structuredContent, isError, null);
        }

        @JsonPropertyOrder({"type", "text"})
        public record Content(String type, String text) {
        }
    }

    /** Only a scalar has to be wrapped for structuredContent; a record, list or node stands as it is. */
    public static Object structured(Object result) {
        return result instanceof String || result instanceof Number || result instanceof Boolean ? Map.of("value", result) : result;
    }

    /** A row of list_actions. */
    @JsonPropertyOrder({"action", "tool", "concept", "meaning", "whoMay", "version", "status"})
    public record ActionRow(String action, String tool, String concept, String meaning, String whoMay, int version, String status) {
    }

    /** A row of list_agents; {@code executes} is the agent's declared list, verbatim. */
    @JsonPropertyOrder({"agent", "kind", "meaning", "runsAs", "autonomy", "executes"})
    public record AgentRow(String agent, String kind, String meaning, String runsAs, String autonomy, JsonNode executes) {
    }

    /** The one tool error that is not a refusal: nothing of that name to explain. */
    public record NotFound(String error) {
    }
}
