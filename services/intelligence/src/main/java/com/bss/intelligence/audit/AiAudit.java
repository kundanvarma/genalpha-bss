package com.bss.intelligence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One AI call, per tenant: what left the box and what came back. */
@Entity
@Table(name = "ai_audit")
public class AiAudit {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** What CLASS of data left on this call (T-P3): none|twin|aggregate|raw-redacted|raw. */
    @Column(length = 16)
    private String exposure;

    @Column(length = 32)
    private String jurisdiction;

    /** T-P4: the per-call retention canary embedded in the outbound prompt. */
    @Column(length = 24)
    private String canary;

    @Column(name = "use_case", nullable = false, length = 64)
    private String useCase;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt", nullable = false, length = 4000)
    private String prompt;

    @Column(name = "response", nullable = false, length = 4000)
    private String response;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "cost_micros")
    private Long costMicros;

    @Column(name = "tier", length = 8)
    private String tier;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** ok | refused-budget | refused-disabled | error */
    @Column(name = "outcome", length = 24)
    private String outcome;

    /** for agent actions: what was done and to which resource */
    @Column(name = "action", length = 64)
    private String action;

    @Column(name = "resource_ref", length = 128)
    private String resourceRef;

    /** true when the provider received the prompt UNREDACTED (tenant opt-in
     * ai-raw-exposure); the prompt/response columns are redacted regardless */
    @Column(name = "raw_exposure")
    private Boolean rawExposure;

    /** how many distinct personal values the redactor replaced on this call */
    @Column(name = "redacted_fields")
    private Integer redactedFields;

    /** who made the call: a digital worker's user id through the model door;
     * null for the BSS's own copilots, whose use case already says what they were */
    @Column(name = "actor_id", length = 64)
    private String actorId;

    /** the worker's badge, as the Workforce page names it */
    @Column(name = "actor_name", length = 128)
    private String actorName;

    /** #422: the provider that failed first when the tenant's FALLBACK served
     * (or was refused) this call; null when the first choice answered */
    @Column(name = "fallback_from", length = 32)
    private String fallbackFrom;

    /** #422: where the token counts came from — provider (its own usage
     * block) | estimate (chars/4); null on a refusal or an action row */
    @Column(name = "token_source", length = 16)
    private String tokenSource;

    public String getFallbackFrom() { return fallbackFrom; }
    public void setFallbackFrom(String v) { this.fallbackFrom = v; }
    public String getTokenSource() { return tokenSource; }
    public void setTokenSource(String v) { this.tokenSource = v; }

    /** #422: the W3C trace id (32 hex) of the call. Joins this row to its
     * trace in a collector, and to the ontology receipt and decision-log row
     * of the same request. Null when tracing was absent. */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }

    public Boolean getRawExposure() { return rawExposure; }
    public void setRawExposure(Boolean v) { this.rawExposure = v; }
    public Integer getRedactedFields() { return redactedFields; }
    public void setRedactedFields(Integer v) { this.redactedFields = v; }
    public String getActorId() { return actorId; }
    public void setActorId(String v) { this.actorId = v; }
    public String getActorName() { return actorName; }
    public void setActorName(String v) { this.actorName = v; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getExposure() { return exposure; }
    public void setExposure(String v) { this.exposure = v; }
    public String getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(String v) { this.jurisdiction = v; }
    public String getCanary() { return canary; }
    public void setCanary(String v) { this.canary = v; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUseCase() { return useCase; }
    public void setUseCase(String useCase) { this.useCase = useCase; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer v) { this.promptTokens = v; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer v) { this.completionTokens = v; }
    public Long getCostMicros() { return costMicros; }
    public void setCostMicros(Long v) { this.costMicros = v; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer v) { this.latencyMs = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceRef() { return resourceRef; }
    public void setResourceRef(String resourceRef) { this.resourceRef = resourceRef; }
}
