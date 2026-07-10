-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE communication_message ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_communication_message_tenant ON communication_message (tenant_id);
