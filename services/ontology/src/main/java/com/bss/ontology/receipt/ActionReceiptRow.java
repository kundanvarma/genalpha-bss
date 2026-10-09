package com.bss.ontology.receipt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One row of the receipt store (ADR 0025). Two rows describe one action: an
 * {@code attempt} committed before dispatch and an {@code outcome} appended
 * after the call returns, sharing a {@code receiptId}.
 *
 * <p>There are no setters for the identity fields and nothing in this service
 * updates a row. "What happened next" is always a new row — that is what makes
 * the chain in {@code prevHash}/{@code rowHash} worth computing, and it is the
 * difference between an evidence log and a status column.
 */
@Entity
@Table(name = "action_receipt")
public class ActionReceiptRow {

    /** phase values; an outcome row always has one of the statuses below. */
    public static final String ATTEMPT = "attempt";
    public static final String OUTCOME = "outcome";

    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    /** The far side may or may not have acted. Only reconciliation can say. */
    public static final String UNCERTAIN = "uncertain";
    /** The check refused it; nothing was dispatched. */
    public static final String REFUSED = "refused";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "receipt_id", nullable = false, length = 64)
    private String receiptId;

    @Column(name = "phase", nullable = false, length = 16)
    private String phase;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "principal", length = 200)
    private String principal;

    @Column(name = "agent", length = 120)
    private String agent;

    @Column(name = "agent_version", length = 40)
    private String agentVersion;

    @Column(name = "authority", length = 400)
    private String authority;

    @Column(name = "action", nullable = false, length = 120)
    private String action;

    @Column(name = "action_version", length = 40)
    private String actionVersion;

    @Column(name = "inputs")
    private String inputs;

    @Column(name = "check_verdict")
    private String checkVerdict;

    @Column(name = "target", length = 400)
    private String target;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "component_status")
    private Integer componentStatus;

    @Column(name = "provider_ref", length = 200)
    private String providerRef;

    @Column(name = "effects")
    private String effects;

    @Column(name = "emits")
    private String emits;

    @Column(name = "refusal", length = 1000)
    private String refusal;

    @Column(name = "compensation_ref", length = 200)
    private String compensationRef;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "row_hash", length = 64)
    private String rowHash;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    protected ActionReceiptRow() {
        // JPA
    }

    public ActionReceiptRow(String id, String receiptId, String phase, String tenantId,
                            String action, OffsetDateTime recordedAt) {
        this.id = id;
        this.receiptId = receiptId;
        this.phase = phase;
        this.tenantId = tenantId;
        this.action = action;
        this.recordedAt = recordedAt;
    }

    public String getId() { return id; }
    public String getReceiptId() { return receiptId; }
    public String getPhase() { return phase; }
    public String getTenantId() { return tenantId; }
    public String getPrincipal() { return principal; }
    public String getAgent() { return agent; }
    public String getAgentVersion() { return agentVersion; }
    public String getAuthority() { return authority; }
    public String getAction() { return action; }
    public String getActionVersion() { return actionVersion; }
    public String getInputs() { return inputs; }
    public String getCheckVerdict() { return checkVerdict; }
    public String getTarget() { return target; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getAttemptNo() { return attemptNo; }
    public String getStatus() { return status; }
    public Integer getComponentStatus() { return componentStatus; }
    public String getProviderRef() { return providerRef; }
    public String getEffects() { return effects; }
    public String getEmits() { return emits; }
    public String getRefusal() { return refusal; }
    public String getCompensationRef() { return compensationRef; }
    public String getPrevHash() { return prevHash; }
    public String getRowHash() { return rowHash; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }

    public void setPrincipal(String v) { this.principal = v; }
    public void setAgent(String v) { this.agent = v; }
    public void setAgentVersion(String v) { this.agentVersion = v; }
    public void setAuthority(String v) { this.authority = v; }
    public void setActionVersion(String v) { this.actionVersion = v; }
    public void setInputs(String v) { this.inputs = v; }
    public void setCheckVerdict(String v) { this.checkVerdict = v; }
    public void setTarget(String v) { this.target = v; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public void setAttemptNo(int v) { this.attemptNo = v; }
    public void setStatus(String v) { this.status = v; }
    public void setComponentStatus(Integer v) { this.componentStatus = v; }
    public void setProviderRef(String v) { this.providerRef = v; }
    public void setEffects(String v) { this.effects = v; }
    public void setEmits(String v) { this.emits = v; }
    public void setRefusal(String v) { this.refusal = v; }
    public void setCompensationRef(String v) { this.compensationRef = v; }
    public void setPrevHash(String v) { this.prevHash = v; }
    public void setRowHash(String v) { this.rowHash = v; }
}
