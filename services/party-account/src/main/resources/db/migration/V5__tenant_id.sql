-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE individual ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE organization ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE billing_account ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE bill_format ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE billing_cycle_specification ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE financial_account ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE party_account ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE settlement_account ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE bill_presentation_media ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_individual_tenant ON individual (tenant_id);
CREATE INDEX idx_organization_tenant ON organization (tenant_id);
CREATE INDEX idx_billing_account_tenant ON billing_account (tenant_id);
CREATE INDEX idx_bill_format_tenant ON bill_format (tenant_id);
CREATE INDEX idx_billing_cycle_specification_tenant ON billing_cycle_specification (tenant_id);
CREATE INDEX idx_financial_account_tenant ON financial_account (tenant_id);
CREATE INDEX idx_party_account_tenant ON party_account (tenant_id);
CREATE INDEX idx_settlement_account_tenant ON settlement_account (tenant_id);
CREATE INDEX idx_bill_presentation_media_tenant ON bill_presentation_media (tenant_id);
