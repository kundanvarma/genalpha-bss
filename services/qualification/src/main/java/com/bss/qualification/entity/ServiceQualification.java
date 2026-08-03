package com.bss.qualification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** An answered TMF645 check, kept: the place asked about, the verdicts
 * given, readable back by id — a qualification is a fact, not a whisper. */
@Entity
@Table(name = "service_qualification")
public class ServiceQualification {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "href")
    private String href;

    @Column(name = "place", length = 2000)
    private String placeJson;

    @Column(name = "result", length = 10000)
    private String resultJson;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "qualification_result", length = 16)
    private String qualificationResult;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** The tenant this row belongs to; never exposed in API responses. */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public ServiceQualification() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getPlaceJson() {
        return placeJson;
    }

    public void setPlaceJson(String placeJson) {
        this.placeJson = placeJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getQualificationResult() {
        return qualificationResult;
    }

    public void setQualificationResult(String qualificationResult) {
        this.qualificationResult = qualificationResult;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
