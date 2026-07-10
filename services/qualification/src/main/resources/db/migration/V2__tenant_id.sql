-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE serviceable_area ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_serviceable_area_tenant ON serviceable_area (tenant_id);
