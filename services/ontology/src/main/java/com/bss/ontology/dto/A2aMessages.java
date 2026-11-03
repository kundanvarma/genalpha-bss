package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The Agent2Agent protocol's shapes (A2A 1.0, JSON-RPC binding) as this server
 * speaks them — the proto's field names in ProtoJSON camelCase, enums as their
 * full names ({@code TASK_STATE_COMPLETED}, {@code ROLE_AGENT}). Only what the
 * door actually uses is declared; a request record ignores what it does not
 * know, so a field a client adds cannot reach the service.
 *
 * <p>Empty and default values are left off ({@code NON_NULL}/{@code NON_EMPTY})
 * where the spec's canonical form omits them, because a signed card is verified
 * against exactly that form (A2A §8.4.1).
 */
public final class A2aMessages {

    private A2aMessages() {
    }

    public static final String PROTOCOL_VERSION = "1.0";
    public static final String BINDING_JSONRPC = "JSONRPC";

    /* ------------------------------------------------------------------ requests */

    /** {@code SendMessage} params. {@code tenant} is never trusted: the token decides the tenant. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendMessageRequest(String tenant, Message message, Configuration configuration) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Configuration(List<String> acceptedOutputModes, Integer historyLength, Boolean returnImmediately) {
    }

    /** {@code GetTask} / {@code CancelTask} params. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskRef(String tenant, String id) {
    }

    /* ------------------------------------------------------------------ both ways */

    @JsonPropertyOrder({"messageId", "contextId", "taskId", "role", "parts"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String messageId, String contextId, String taskId, String role, List<Part> parts) {
        public static final String USER = "ROLE_USER";
        public static final String AGENT = "ROLE_AGENT";
    }

    /** One part: text, or structured data (a JSON value), with its media type. */
    @JsonPropertyOrder({"text", "data", "mediaType"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text, JsonNode data, String mediaType) {
        public static Part text(String text) {
            return new Part(text, null, "text/plain");
        }

        public static Part data(JsonNode data) {
            return new Part(null, data, "application/json");
        }
    }

    /* ------------------------------------------------------------------ tasks */

    @JsonPropertyOrder({"id", "contextId", "status", "artifacts", "metadata"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Task(String id, String contextId, TaskStatus status, List<Artifact> artifacts, TaskFacts metadata) {
    }

    @JsonPropertyOrder({"state", "message", "timestamp"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStatus(String state, Message message, String timestamp) {
        public static final String SUBMITTED = "TASK_STATE_SUBMITTED";
        public static final String WORKING = "TASK_STATE_WORKING";
        public static final String COMPLETED = "TASK_STATE_COMPLETED";
        public static final String FAILED = "TASK_STATE_FAILED";
        public static final String INPUT_REQUIRED = "TASK_STATE_INPUT_REQUIRED";
        public static final String REJECTED = "TASK_STATE_REJECTED";
    }

    @JsonPropertyOrder({"artifactId", "name", "parts"})
    public record Artifact(String artifactId, String name, List<Part> parts) {
    }

    /**
     * The house facts of a task, in {@code Task.metadata}: which skill, which governed
     * action, whether it was a dry run, and the receipt — the decision id an auditor
     * finds in the decision log. {@code receipt} says in words what was (or was not) written.
     */
    @JsonPropertyOrder({"skill", "action", "dryRun", "decisionId", "approvalId", "receipt"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskFacts(String skill, String action, Boolean dryRun, String decisionId, String approvalId, String receipt) {
    }

    /** {@code SendMessage} answers a task (never a bare message: every delegation gets an id). */
    public record SendMessageResponse(Task task) {
    }

    /** One entry of a JSON-RPC error's {@code data} array (A2A §9.5: ProtoJSON {@code Any}, google.rpc.ErrorInfo). */
    @JsonPropertyOrder({"@type", "reason", "domain", "metadata"})
    public record ErrorInfo(@JsonProperty("@type") String type, String reason, String domain, Map<String, String> metadata) {
        public static ErrorInfo of(String reason, Map<String, String> metadata) {
            return new ErrorInfo("type.googleapis.com/google.rpc.ErrorInfo", reason, "a2a-protocol.org", metadata);
        }
    }

    /* ------------------------------------------------------------------ the Agent Card */

    @JsonPropertyOrder({"name", "description", "supportedInterfaces", "version", "documentationUrl", "capabilities",
            "securitySchemes", "securityRequirements", "defaultInputModes", "defaultOutputModes", "skills", "signatures"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record AgentCard(String name, String description, List<AgentInterface> supportedInterfaces, String version,
            String documentationUrl, Capabilities capabilities, Map<String, SecurityScheme> securitySchemes,
            List<SecurityRequirement> securityRequirements, List<String> defaultInputModes, List<String> defaultOutputModes,
            List<Skill> skills, List<Signature> signatures) {

        public AgentCard signed(List<Signature> with) {
            return new AgentCard(name, description, supportedInterfaces, version, documentationUrl, capabilities, securitySchemes,
                    securityRequirements, defaultInputModes, defaultOutputModes, skills, with);
        }
    }

    @JsonPropertyOrder({"url", "protocolBinding", "protocolVersion"})
    public record AgentInterface(String url, String protocolBinding, String protocolVersion) {
    }

    /** Optional booleans the spec keeps when explicitly set: we say plainly what we do not do. */
    @JsonPropertyOrder({"streaming", "pushNotifications", "extendedAgentCard"})
    public record Capabilities(boolean streaming, boolean pushNotifications, boolean extendedAgentCard) {
    }

    /** A {@code SecurityScheme} oneof: this door has exactly one, OAuth 2.0. */
    public record SecurityScheme(OAuth2SecurityScheme oauth2SecurityScheme) {
    }

    @JsonPropertyOrder({"description", "flows", "oauth2MetadataUrl"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record OAuth2SecurityScheme(String description, OAuthFlows flows, String oauth2MetadataUrl) {
    }

    /** The {@code OAuthFlows} oneof: authorization code — the partner's agent acts for a person who signed in. */
    public record OAuthFlows(AuthorizationCodeFlow authorizationCode) {
    }

    /** {@code scopes} is REQUIRED by the spec and written even when empty: the realm grants roles, not scopes. */
    @JsonPropertyOrder({"authorizationUrl", "tokenUrl", "scopes"})
    public record AuthorizationCodeFlow(String authorizationUrl, String tokenUrl,
            @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, String> scopes) {
    }

    public record SecurityRequirement(Map<String, StringList> schemes) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record StringList(List<String> list) {
    }

    @JsonPropertyOrder({"id", "name", "description", "tags", "examples", "inputModes", "outputModes"})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Skill(String id, String name, String description, List<String> tags, List<String> examples,
            List<String> inputModes, List<String> outputModes) {
    }

    /** A detached JWS over the RFC 8785 form of the card without {@code signatures} (A2A §8.4.2). */
    @JsonPropertyOrder({"protected", "signature"})
    public record Signature(@JsonProperty("protected") String protectedHeader, String signature) {
    }
}
