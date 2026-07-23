package com.bss.cart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * An Agentic Commerce Protocol checkout session — the merchant-side state an
 * AI shopping agent drives through create → update → complete. Underneath it
 * is a TMF663 cart (cartId): the agent channel rides the same commerce spine
 * as every human channel, it just wears the protocol's shape.
 */
@Entity
@Table(name = "acp_session")
public class AcpSession {

    public static final String READY = "ready_for_payment";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "cart_id", nullable = false)
    private String cartId;

    @Column(nullable = false)
    private String status;

    private String currency;

    /** ACP line items with resolved prices, as sent to the agent. */
    @Column(name = "line_item_json", length = 8000)
    private String lineItemJson;

    @Column(name = "buyer_json", length = 2000)
    private String buyerJson;

    @Column(name = "completed_order_id")
    private String completedOrderId;

    @Column(name = "completed_payment_id")
    private String completedPaymentId;

    /** The Idempotency-Key that completed this session: replays return the
     * same order instead of buying twice. */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_update")
    private OffsetDateTime lastUpdate;

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

    public String getCartId() {
        return cartId;
    }

    public void setCartId(String cartId) {
        this.cartId = cartId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getLineItemJson() {
        return lineItemJson;
    }

    public void setLineItemJson(String lineItemJson) {
        this.lineItemJson = lineItemJson;
    }

    public String getBuyerJson() {
        return buyerJson;
    }

    public void setBuyerJson(String buyerJson) {
        this.buyerJson = buyerJson;
    }

    public String getCompletedOrderId() {
        return completedOrderId;
    }

    public void setCompletedOrderId(String completedOrderId) {
        this.completedOrderId = completedOrderId;
    }

    public String getCompletedPaymentId() {
        return completedPaymentId;
    }

    public void setCompletedPaymentId(String completedPaymentId) {
        this.completedPaymentId = completedPaymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OffsetDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
