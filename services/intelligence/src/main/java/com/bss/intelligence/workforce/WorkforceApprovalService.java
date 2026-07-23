package com.bss.intelligence.workforce;

import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The T3 gate. A worker FILES a high-blast-radius action as data; nothing
 * executes. A human APPROVES and the stored request is forwarded through
 * the gateway with the APPROVER'S own token — if the approver lacks the
 * authority, the domain service refuses them exactly as it would refuse
 * anyone. Refusals keep their receipt too.
 */
@Service
public class WorkforceApprovalService {

    private static final Set<String> METHODS = Set.of("POST", "PATCH", "DELETE");

    private final WorkforceApprovalRepository approvals;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;
    private final RestClient gateway;

    public WorkforceApprovalService(WorkforceApprovalRepository approvals, TenantScope tenantScope,
            ObjectMapper objectMapper, RestClient.Builder builder,
            @Value("${bss.workforce.gateway-base-url:http://localhost:8080}") String gatewayBaseUrl) {
        this.approvals = approvals;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.gateway = builder.baseUrl(gatewayBaseUrl).build();
    }

    @Transactional
    public Map<String, Object> file(Map<String, Object> body) {
        String action = str(body.get("action"));
        String method = str(body.get("method"));
        String path = str(body.get("path"));
        String reason = str(body.get("reason"));
        if (action == null || method == null || path == null || reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action, method, path and reason are all required");
        }
        method = method.toUpperCase();
        if (!METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "method must be POST, PATCH or DELETE");
        }
        // Only the TMF doors: an approval can never point back at the AI
        // plane, the agent surfaces, or anywhere outside the domain APIs.
        if (!path.startsWith("/tmf-api/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "path must be a /tmf-api/ door");
        }
        WorkforceApproval row = new WorkforceApproval();
        row.setId("apr_" + UUID.randomUUID());
        row.setTenantId(tenantScope.currentTenantId());
        row.setRequestedBy(callerId());
        row.setRequestedByName(WorkforceService.callerName());
        row.setAction(action);
        row.setMethod(method);
        row.setPath(path);
        row.setBodyJson(body.get("body") == null ? null : writeJson(body.get("body")));
        row.setReason(reason);
        row.setStatus(WorkforceApproval.PENDING);
        row.setCreatedAt(OffsetDateTime.now());
        return view(approvals.save(row));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String tenant = tenantScope.currentTenantId();
        List<WorkforceApproval> rows = status == null
                ? approvals.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)
                : approvals.findByTenantIdAndStatusOrderByCreatedAtDesc(tenant, status);
        return rows.stream().map(this::view).toList();
    }

    /**
     * Approve = EXECUTE, as the approver: the stored request rides the
     * approver's own Authorization through the gateway. A downstream
     * refusal leaves the approval PENDING with the error surfaced — the
     * human can fix and retry, or refuse it.
     */
    @Transactional
    public Map<String, Object> approve(String id, String authorization, Map<String, Object> body) {
        WorkforceApproval row = pending(id);
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "approval executes with the APPROVER'S token — none was presented");
        }
        String result;
        try {
            RestClient.RequestBodySpec req = gateway
                    .method(HttpMethod.valueOf(row.getMethod()))
                    .uri(row.getPath())
                    .header("Authorization", authorization);
            if (row.getBodyJson() != null) {
                req = (RestClient.RequestBodySpec) req
                        .header("Content-Type", "application/json").body(row.getBodyJson());
            }
            result = req.retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the action was refused downstream (approval stays pending): "
                            + e.getResponseBodyAsString());
        }
        row.setStatus(WorkforceApproval.APPROVED);
        row.setDecidedBy(callerId());
        row.setDecidedByName(WorkforceService.callerName());
        row.setDecisionNote(body == null ? null : str(body.get("note")));
        row.setResultJson(result == null ? null
                : result.length() > 1900 ? result.substring(0, 1900) : result);
        row.setDecidedAt(OffsetDateTime.now());
        return view(approvals.save(row));
    }

    @Transactional
    public Map<String, Object> refuse(String id, Map<String, Object> body) {
        WorkforceApproval row = pending(id);
        row.setStatus(WorkforceApproval.REFUSED);
        row.setDecidedBy(callerId());
        row.setDecidedByName(WorkforceService.callerName());
        row.setDecisionNote(body == null ? null : str(body.get("note")));
        row.setDecidedAt(OffsetDateTime.now());
        return view(approvals.save(row));
    }

    private WorkforceApproval pending(String id) {
        WorkforceApproval row = approvals.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no such approval"));
        if (!WorkforceApproval.PENDING.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "approval is already " + row.getStatus());
        }
        return row;
    }

    private Map<String, Object> view(WorkforceApproval row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.getId());
        map.put("action", row.getAction());
        map.put("method", row.getMethod());
        map.put("path", row.getPath());
        if (row.getBodyJson() != null) {
            map.put("body", readJson(row.getBodyJson()));
        }
        map.put("reason", row.getReason());
        map.put("status", row.getStatus());
        map.put("requestedBy", row.getRequestedBy());
        map.put("requestedByName", row.getRequestedByName());
        map.put("createdAt", row.getCreatedAt());
        if (row.getDecidedBy() != null) {
            map.put("decidedBy", row.getDecidedBy());
            map.put("decidedByName", row.getDecidedByName());
            map.put("decidedAt", row.getDecidedAt());
        }
        if (row.getDecisionNote() != null) {
            map.put("decisionNote", row.getDecisionNote());
        }
        if (row.getResultJson() != null) {
            map.put("result", readJson(row.getResultJson()));
        }
        return map;
    }

    private String callerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
