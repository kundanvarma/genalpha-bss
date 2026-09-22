package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

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
        @JsonPropertyOrder({"code", "message"})
        public record Error(int code, String message) {
        }
    }

    @JsonPropertyOrder({"protocolVersion", "capabilities", "serverInfo", "instructions"})
    public record Initialize(String protocolVersion, Capabilities capabilities, ServerInfo serverInfo, String instructions) {
        public record Capabilities(Tools tools) {
        }

        public record Tools(boolean listChanged) {
        }

        @JsonPropertyOrder({"name", "version"})
        public record ServerInfo(String name, String version) {
        }
    }

    public record ToolList(List<Tool> tools) {
    }

    /** One generated tool: its name, the action's meaning as its description, and a JSON schema of the action's inputs. */
    @JsonPropertyOrder({"name", "description", "inputSchema"})
    public record Tool(String name, String description, InputSchema inputSchema) {
    }

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
    @JsonPropertyOrder({"content", "structuredContent", "isError"})
    public record ToolResult(List<Content> content, Object structuredContent, boolean isError) {
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
