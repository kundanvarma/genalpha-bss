package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.A2aMessages;
import com.bss.ontology.dto.A2aMessages.Artifact;
import com.bss.ontology.dto.A2aMessages.ErrorInfo;
import com.bss.ontology.dto.A2aMessages.Message;
import com.bss.ontology.dto.A2aMessages.Part;
import com.bss.ontology.dto.A2aMessages.SendMessageRequest;
import com.bss.ontology.dto.A2aMessages.Task;
import com.bss.ontology.dto.A2aMessages.TaskRef;
import com.bss.ontology.dto.A2aMessages.TaskStatus;
import com.bss.ontology.dto.McpMessages;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.A2aSkills;
import com.bss.ontology.service.A2aTasks;
import com.bss.ontology.service.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The A2A 1.0 server face (JSON-RPC binding, #426): a partner's agent delegates a
 * task — {@code SendMessage} with a data part naming a skill and its inputs — and
 * reads it back with {@code GetTask}. Behind the same token rule as every other
 * face of the registry: no token, 401; the token's issuer decides the tenant; the
 * token's person is who the work is done for; the token's {@code act} claim (or the
 * agent's own header) names the agent on the receipt.
 *
 * <p>Every skill runs through {@link A2aSkills}, which calls the existing check and
 * execute services — so preconditions, permissions, policy, the autonomy gate, the
 * approval ladder and the receipts all apply. This class only speaks the protocol.
 * Each task is answered synchronously and is already final when {@code SendMessage}
 * returns, so there is nothing to cancel and nothing to stream.
 */
@RestController
public class A2aController {

    static final String VERSION_HEADER = "A2A-Version";
    static final int TASK_NOT_FOUND = -32001;
    static final int TASK_NOT_CANCELABLE = -32002;
    static final int UNSUPPORTED_OPERATION = -32004;
    static final int CONTENT_TYPE_NOT_SUPPORTED = -32005;
    static final int EXTENDED_CARD_NOT_CONFIGURED = -32007;
    static final int VERSION_NOT_SUPPORTED = -32009;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    /** The 0.3 method names: a client using them speaks a version whose shapes this door does not. */
    private static final Set<String> LEGACY = Set.of("message/send", "message/stream", "tasks/get", "tasks/cancel",
            "tasks/resubscribe", "tasks/pushNotificationConfig/set", "tasks/pushNotificationConfig/get");
    private static final Set<String> NOT_OFFERED = Set.of("SendStreamingMessage", "SubscribeToTask", "ListTasks",
            "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig", "ListTaskPushNotificationConfigs",
            "DeleteTaskPushNotificationConfig");
    private static final List<String> OUTPUT_MODES = List.of("application/json", "text/plain");

    private final A2aSkills skills;
    private final A2aTasks tasks;
    private final TenantScope tenantScope;
    private final ObjectMapper json;

    public A2aController(A2aSkills skills, A2aTasks tasks, TenantScope tenantScope, ObjectMapper json) {
        this.skills = skills;
        this.tasks = tasks;
        this.tenantScope = tenantScope;
        this.json = json;
    }

    /** A JSON-RPC answer is always HTTP 200; the outcome is in the body (A2A §9.5). */
    @PostMapping(ApiConstants.A2A_PATH)
    public Object rpc(@RequestBody JsonNode body, HttpServletRequest request) {
        JsonNode id = body == null ? null : body.get("id");
        if (body == null || !body.isObject() || !"2.0".equals(body.path("jsonrpc").asString("")) || !body.path("method").isString()) {
            return error(id, INVALID_REQUEST, "Request payload validation error: a JSON-RPC 2.0 request object is expected", null);
        }
        String method = body.path("method").asString();
        String version = version(request);
        if (!A2aMessages.PROTOCOL_VERSION.equals(version) || LEGACY.contains(method)) {
            return error(id, VERSION_NOT_SUPPORTED, "A2A " + version + " is not supported: this agent speaks A2A "
                    + A2aMessages.PROTOCOL_VERSION + " (send " + VERSION_HEADER + ": 1.0 and the methods SendMessage, GetTask, CancelTask)",
                    List.of(ErrorInfo.of("VERSION_NOT_SUPPORTED", Map.of("requested", version, "supported", A2aMessages.PROTOCOL_VERSION))));
        }
        String tenant = tenantScope.currentTenantId();
        JsonNode params = body.path("params");
        if (params.has("tenant") && !params.path("tenant").isNull() && !tenant.equals(params.path("tenant").asString())) {
            return error(id, INVALID_PARAMS, "Invalid parameters: tenant is taken from your token, not from the request", null);
        }
        Caller caller = Caller.current(request, tenant);
        if (caller.agent() == null) {
            caller = caller.withAgent(A2aSkills.DEFAULT_AGENT);
        }
        return switch (method) {
            case "SendMessage" -> send(id, params, caller);
            case "GetTask" -> get(id, params, caller);
            case "CancelTask" -> cancel(id, params, caller);
            case "GetExtendedAgentCard" -> error(id, EXTENDED_CARD_NOT_CONFIGURED,
                    "this agent has no extended card; the public card at " + ApiConstants.AGENT_CARD + " is the whole of it", null);
            default -> NOT_OFFERED.contains(method)
                    ? error(id, UNSUPPORTED_OPERATION, method + " is not offered: every task is answered at once by SendMessage", null)
                    : error(id, METHOD_NOT_FOUND, "Method not found: " + method, null);
        };
    }

    private Object send(JsonNode id, JsonNode params, Caller caller) {
        SendMessageRequest req;
        try {
            req = json.treeToValue(params, SendMessageRequest.class);
        } catch (RuntimeException e) {
            return error(id, INVALID_PARAMS, "Invalid parameters: params is not a SendMessageRequest", null);
        }
        Message m = req == null ? null : req.message();
        if (m == null || m.messageId() == null || m.messageId().isBlank() || m.parts() == null || m.parts().isEmpty()) {
            return error(id, INVALID_PARAMS, "Invalid parameters: message with a messageId and at least one part is required", null);
        }
        if (!Message.USER.equals(m.role())) {
            return error(id, INVALID_PARAMS, "Invalid parameters: a delegated message has role ROLE_USER", null);
        }
        List<String> accepted = req.configuration() == null ? null : req.configuration().acceptedOutputModes();
        if (accepted != null && !accepted.isEmpty() && accepted.stream().noneMatch(OUTPUT_MODES::contains)) {
            return error(id, CONTENT_TYPE_NOT_SUPPORTED, "this agent answers in application/json and text/plain only", null);
        }
        JsonNode ask = null;
        for (Part p : m.parts()) {
            if (p.data() != null && p.data().isObject()) {
                ask = p.data();
                break;
            }
        }
        String skill = ask == null || !ask.path("skill").isString() ? null : ask.path("skill").asString();
        A2aSkills.Result r = skills.run(skill, ask == null ? null : ask.get("inputs"), caller);

        String taskId = UUID.randomUUID().toString();
        String contextId = m.contextId() == null || m.contextId().isBlank() ? UUID.randomUUID().toString() : m.contextId();
        Message said = new Message(UUID.randomUUID().toString(), contextId, taskId, Message.AGENT, List.of(Part.text(r.said())));
        List<Artifact> artifacts = r.result() == null ? List.of()
                : List.of(new Artifact(UUID.randomUUID().toString(), "result",
                        List.of(Part.data(json.valueToTree(r.result())))));
        Task task = new Task(taskId, contextId,
                new TaskStatus(r.state(), said, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()), artifacts, r.facts());
        tasks.put(caller.tenant(), caller.subject(), task);
        return new McpMessages.Reply("2.0", id, new A2aMessages.SendMessageResponse(task));
    }

    private Object get(JsonNode id, JsonNode params, Caller caller) {
        TaskRef ref = ref(params);
        if (ref == null) {
            return error(id, INVALID_PARAMS, "Invalid parameters: id is required", null);
        }
        return tasks.get(ref.id(), caller.tenant(), caller.subject())
                .<Object>map(t -> new McpMessages.Reply("2.0", id, t))
                .orElseGet(() -> notFound(id, ref.id()));
    }

    private Object cancel(JsonNode id, JsonNode params, Caller caller) {
        TaskRef ref = ref(params);
        if (ref == null) {
            return error(id, INVALID_PARAMS, "Invalid parameters: id is required", null);
        }
        return tasks.get(ref.id(), caller.tenant(), caller.subject())
                .<Object>map(t -> error(id, TASK_NOT_CANCELABLE, "Task cannot be canceled: it is already "
                        + t.status().state().replace("TASK_STATE_", "").toLowerCase().replace('_', '-'), null))
                .orElseGet(() -> notFound(id, ref.id()));
    }

    private TaskRef ref(JsonNode params) {
        try {
            TaskRef ref = json.treeToValue(params, TaskRef.class);
            return ref == null || ref.id() == null || ref.id().isBlank() ? null : ref;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object notFound(JsonNode id, String taskId) {
        return error(id, TASK_NOT_FOUND, "Task not found", List.of(ErrorInfo.of("TASK_NOT_FOUND", Map.of("taskId", taskId))));
    }

    /** The version the client speaks: the header, else the request parameter; empty means 0.3 (A2A §3.6). */
    private static String version(HttpServletRequest request) {
        String v = request.getHeader(VERSION_HEADER);
        if (v == null || v.isBlank()) {
            v = request.getParameter(VERSION_HEADER);
        }
        return v == null || v.isBlank() ? "0.3" : v.trim();
    }

    private static McpMessages.ErrorReply error(JsonNode id, int code, String message, Object data) {
        return new McpMessages.ErrorReply("2.0", id, new McpMessages.ErrorReply.Error(code, message, data));
    }
}
