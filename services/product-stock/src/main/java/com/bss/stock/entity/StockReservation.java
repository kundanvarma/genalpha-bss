package com.bss.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "stock_reservation")
public class StockReservation {

    public static final String ACTIVE = "active";
    public static final String COMPLETED = "completed";
    public static final String RELEASED = "released";

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "product_stock_id", nullable = false, length = 36)
    private String productStockId;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public StockReservation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProductStockId() {
        return productStockId;
    }

    public void setProductStockId(String productStockId) {
        this.productStockId = productStockId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
