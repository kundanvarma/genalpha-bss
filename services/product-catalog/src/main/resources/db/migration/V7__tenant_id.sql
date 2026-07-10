-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE category ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE product_specification ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE product_offering ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE product_offering_price ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_category_tenant ON category (tenant_id);
CREATE INDEX idx_product_specification_tenant ON product_specification (tenant_id);
CREATE INDEX idx_product_offering_tenant ON product_offering (tenant_id);
CREATE INDEX idx_product_offering_price_tenant ON product_offering_price (tenant_id);
