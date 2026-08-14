package com.bss.insight.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * A first-party CUSTOMER trait, projected from the BSS's own operational events
 * (orders, billing, usage, loyalty, churn scores). This is the BSS-native
 * feature store: audiences resolve against these traits directly — no dump to a
 * marketing tool, no reverse-ETL round-trip to a warehouse and back. One row per
 * (party, key, value) so multi-valued traits (a party holds several products)
 * are natural. Keys are namespaced by source, e.g. "product", "churnRisk".
 */
@Entity
@Table(name = "party_trait")
public class PartyTrait {

    @Id
    private String id;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "party_id")
    private String partyId;

    @Column(name = "trait_key")
    private String traitKey;

    @Column(name = "trait_value")
    private String traitValue;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getTraitKey() { return traitKey; }
    public void setTraitKey(String traitKey) { this.traitKey = traitKey; }
    public String getTraitValue() { return traitValue; }
    public void setTraitValue(String traitValue) { this.traitValue = traitValue; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
