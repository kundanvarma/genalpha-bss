package com.bss.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Who started a redirect payment session, recorded when the session is created
 * so that confirming it cannot hand the payment to somebody else (issue #250).
 *
 * A session is an INTENT, not a payment: most become one, some are abandoned,
 * and TMF676 Payment means an attempted or completed payment. So this is its own
 * row rather than a pending {@code payment}, which would change what every
 * channel's {@code GET /payment} returns.
 *
 * {@code ownerPartyId} is null for a guest checkout (no party yet) and for an
 * operator-initiated session (no customer scope). A null owner means "unknown",
 * never "anyone's" — see PaymentService.confirmSession.
 */
@Entity
@Table(name = "psp_redirect_session")
public class PspRedirectSession {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** The PSP's own session id — what the return leg and the webhook quote. */
    @Column(name = "session_ref", nullable = false)
    private String sessionRef;

    @Column(nullable = false)
    private String provider;

    @Column(name = "owner_party_id")
    private String ownerPartyId;

    @Column(name = "amount_value")
    private BigDecimal amountValue;

    @Column(name = "amount_unit")
    private String amountUnit;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSessionRef() { return sessionRef; }
    public void setSessionRef(String sessionRef) { this.sessionRef = sessionRef; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getOwnerPartyId() { return ownerPartyId; }
    public void setOwnerPartyId(String ownerPartyId) { this.ownerPartyId = ownerPartyId; }

    public BigDecimal getAmountValue() { return amountValue; }
    public void setAmountValue(BigDecimal amountValue) { this.amountValue = amountValue; }

    public String getAmountUnit() { return amountUnit; }
    public void setAmountUnit(String amountUnit) { this.amountUnit = amountUnit; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
