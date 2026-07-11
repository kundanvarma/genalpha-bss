package com.bss.intelligence.churn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** What the trainer fit for one tenant: standardization + weights, as JSON. */
@Entity
@Table(name = "churn_model")
public class ChurnModelRecord {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "parameters", nullable = false, length = 4000)
    private String parameters;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "positives", nullable = false)
    private int positives;

    @Column(name = "trained_at", nullable = false)
    private OffsetDateTime trainedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public int getPositives() { return positives; }
    public void setPositives(int positives) { this.positives = positives; }
    public OffsetDateTime getTrainedAt() { return trainedAt; }
    public void setTrainedAt(OffsetDateTime trainedAt) { this.trainedAt = trainedAt; }
}
