-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE usage_specification ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE usage_allowance ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE usage_record ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE rated_charge ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_usage_specification_tenant ON usage_specification (tenant_id);
CREATE INDEX idx_usage_allowance_tenant ON usage_allowance (tenant_id);
CREATE INDEX idx_usage_record_tenant ON usage_record (tenant_id);
CREATE INDEX idx_rated_charge_tenant ON rated_charge (tenant_id);
