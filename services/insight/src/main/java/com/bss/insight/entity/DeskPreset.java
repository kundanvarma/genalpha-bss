package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Pre-filled values for a desk form, born from a repeated pattern the desk noticed. */
@Entity
@Table(name = "desk_preset")
public class DeskPreset {

    @Id
    private String id;
    @Column(name = "tenant_id")
    private String tenantId;
    private String desk;
    private String form;
    private String name;
    @Column(name = "values_json", columnDefinition = "TEXT")
    private String valuesJson;
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { tenantId = v; }
    public String getDesk() { return desk; }
    public void setDesk(String v) { desk = v; }
    public String getForm() { return form; }
    public void setForm(String v) { form = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getValuesJson() { return valuesJson; }
    public void setValuesJson(String v) { valuesJson = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
}
