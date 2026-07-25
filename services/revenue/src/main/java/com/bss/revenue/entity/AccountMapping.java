package com.bss.revenue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** Posting rules as data: finance's own account code + name per posting key. */
@Entity
@Table(name = "account_mapping")
@IdClass(AccountMapping.Key.class)
public class AccountMapping {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Id
    @Column(name = "mapping_key")
    private String mappingKey;

    @Column(name = "account_code")
    private String accountCode;

    @Column(name = "account_name")
    private String accountName;

    /** Key-specific dial: 'tax' = VAT percent; 'loyalty:liability' = currency per point. */
    @Column(name = "config_value")
    private java.math.BigDecimal configValue;

    public java.math.BigDecimal getConfigValue() {
        return configValue;
    }

    public void setConfigValue(java.math.BigDecimal configValue) {
        this.configValue = configValue;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getMappingKey() {
        return mappingKey;
    }

    public void setMappingKey(String mappingKey) {
        this.mappingKey = mappingKey;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public static class Key implements Serializable {
        private String tenantId;
        private String mappingKey;

        public Key() {
        }

        public Key(String tenantId, String mappingKey) {
            this.tenantId = tenantId;
            this.mappingKey = mappingKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId)
                    && Objects.equals(mappingKey, k.mappingKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, mappingKey);
        }
    }
}
