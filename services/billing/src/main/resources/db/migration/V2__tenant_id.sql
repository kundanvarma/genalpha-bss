-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE customer_bill ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE applied_billing_rate ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_customer_bill_tenant ON customer_bill (tenant_id);
CREATE INDEX idx_applied_billing_rate_tenant ON applied_billing_rate (tenant_id);

-- "One bill per customer per period" is a per-tenant rule now: the same
-- party id may legitimately be billed by several tenants in one period.
DROP INDEX ux_customer_bill_owner_period;
CREATE UNIQUE INDEX ux_customer_bill_owner_period ON customer_bill (tenant_id, owner_party_id, period_start);
