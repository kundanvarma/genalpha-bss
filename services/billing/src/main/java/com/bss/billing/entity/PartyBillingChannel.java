package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One party's consent to one bill-delivery channel. Rows are the consent
 * state; the ORDER (einvoice rail -> mailbox -> print) is code, and the
 * rail is re-checked per send — a stale alias falls through by itself.
 */
@Entity
@Table(name = "party_billing_channel")
public class PartyBillingChannel {

    public static final String EFAKTURA = "efaktura";
    public static final String MAILBOX = "mailbox";
    public static final String PRINT = "print";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "party_id", nullable = false)
    private String partyId;

    @Column(nullable = false)
    private String channel;

    @Column(name = "alias_ref")
    private String aliasRef;

    @Column(name = "consent_at", nullable = false)
    private OffsetDateTime consentAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_update", nullable = false)
    private OffsetDateTime lastUpdate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPartyId() { return partyId; }
    public void setPartyId(String partyId) { this.partyId = partyId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getAliasRef() { return aliasRef; }
    public void setAliasRef(String aliasRef) { this.aliasRef = aliasRef; }
    public OffsetDateTime getConsentAt() { return consentAt; }
    public void setConsentAt(OffsetDateTime consentAt) { this.consentAt = consentAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(OffsetDateTime lastUpdate) { this.lastUpdate = lastUpdate; }
}
