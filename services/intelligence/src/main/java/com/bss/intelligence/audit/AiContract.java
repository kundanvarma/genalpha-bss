package com.bss.intelligence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * The in-life state of one MODEL CONTRACT (TMF915): may this scenario call
 * a model right now? The tenant kill-switch is the fleet-wide brake; this
 * row is the per-scenario one. No row means active — contracts are born
 * from use, not from registration ceremonies.
 */
@Entity
@Table(name = "ai_contract")
public class AiContract {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "use_case", nullable = false, length = 64)
    private String useCase;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

    public AiContract() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUseCase() {
        return useCase;
    }

    public void setUseCase(String useCase) {
        this.useCase = useCase;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
