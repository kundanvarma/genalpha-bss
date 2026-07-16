package com.bss.som.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** The SIM in the box: an activation code, a pre-minted ICCID/PUK, and
 * the dealer attribution BAKED IN — a kit sold like a chocolate bar
 * still credits the store that sold it. */
@Entity
@Table(name = "starter_kit")
public class StarterKit {

    public static final String AVAILABLE = "available";
    public static final String ACTIVATED = "activated";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "activation_code", nullable = false)
    private String activationCode;

    @Column(nullable = false)
    private String iccid;

    @Column(name = "puk_ciphertext", nullable = false)
    private String pukCiphertext;

    @Column(name = "dealer_org_id", nullable = false)
    private String dealerOrgId;

    private String store;

    @Column(nullable = false)
    private String status;

    @Column(name = "product_order_id")
    private String productOrderId;

    @Column(name = "activated_by")
    private String activatedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getActivationCode() { return activationCode; }
    public void setActivationCode(String v) { this.activationCode = v; }
    public String getIccid() { return iccid; }
    public void setIccid(String v) { this.iccid = v; }
    public String getPukCiphertext() { return pukCiphertext; }
    public void setPukCiphertext(String v) { this.pukCiphertext = v; }
    public String getDealerOrgId() { return dealerOrgId; }
    public void setDealerOrgId(String v) { this.dealerOrgId = v; }
    public String getStore() { return store; }
    public void setStore(String v) { this.store = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getProductOrderId() { return productOrderId; }
    public void setProductOrderId(String v) { this.productOrderId = v; }
    public String getActivatedBy() { return activatedBy; }
    public void setActivatedBy(String v) { this.activatedBy = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime v) { this.activatedAt = v; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime v) { this.lastUpdate = v; }
}
