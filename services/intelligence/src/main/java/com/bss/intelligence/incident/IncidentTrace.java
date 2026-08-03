package com.bss.intelligence.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One investigation, remembered: what failed, what the agent thought,
 * what the human said. Episodic memory — the raw material of runbooks. */
@Entity
@Table(name = "incident_trace")
public class IncidentTrace {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    private String signature;

    @Column(name = "process_flow_id")
    private String processFlowId;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "spec_code")
    private String specCode;

    @Column(name = "task_code")
    private String taskCode;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "context_digest")
    private String contextDigest;

    private String hypothesis;

    private BigDecimal confidence;

    @Column(name = "proposed_action")
    private String proposedAction;

    private String source;

    @Column(name = "ticket_id")
    private String ticketId;

    private String verdict;

    @Column(name = "verdict_note")
    private String verdictNote;

    @Column(name = "diagnose_ms")
    private Long diagnoseMs;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSignature() { return signature; }
    public void setSignature(String v) { this.signature = v; }
    public String getProcessFlowId() { return processFlowId; }
    public void setProcessFlowId(String v) { this.processFlowId = v; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String v) { this.productOrderId = v; }
    public String getSpecCode() { return specCode; }
    public void setSpecCode(String v) { this.specCode = v; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String v) { this.taskCode = v; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String v) { this.partyId = v; }
    public String getContextDigest() { return contextDigest; }
    public void setContextDigest(String v) { this.contextDigest = v; }
    public String getHypothesis() { return hypothesis; }
    public void setHypothesis(String v) { this.hypothesis = v; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal v) { this.confidence = v; }
    public String getProposedAction() { return proposedAction; }
    public void setProposedAction(String v) { this.proposedAction = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getTicketId() { return ticketId; }
    public void setTicketId(String v) { this.ticketId = v; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String v) { this.verdict = v; }
    public String getVerdictNote() { return verdictNote; }
    public void setVerdictNote(String v) { this.verdictNote = v; }
    public Long getDiagnoseMs() { return diagnoseMs; }
    public void setDiagnoseMs(Long v) { this.diagnoseMs = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
