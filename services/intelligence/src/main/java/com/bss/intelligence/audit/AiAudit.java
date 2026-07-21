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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
