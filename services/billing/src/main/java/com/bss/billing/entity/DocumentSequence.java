package com.bss.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** A gapless per-tenant series — incremented under a row lock. */
@Entity
@Table(name = "document_sequence")
@IdClass(DocumentSequence.Key.class)
public class DocumentSequence {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Id
    private String series;

    @Column(name = "next_value")
    private long nextValue;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getSeries() { return series; }
    public void setSeries(String v) { this.series = v; }
    public long getNextValue() { return nextValue; }
    public void setNextValue(long v) { this.nextValue = v; }

    public static class Key implements Serializable {
        private String tenantId;
        private String series;

        public Key() {
        }

        public Key(String tenantId, String series) {
            this.tenantId = tenantId;
            this.series = series;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId)
                    && Objects.equals(series, k.series);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, series);
        }
    }
}
