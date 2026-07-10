-- Multitenancy (pool model): every domain row belongs to exactly one tenant.
-- Existing single-tenant data becomes tenant 'genalpha'. The DEFAULT stays as
-- a safety net; application code always sets tenant_id explicitly.
ALTER TABLE product_stock ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';
ALTER TABLE stock_reservation ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'genalpha';

CREATE INDEX idx_product_stock_tenant ON product_stock (tenant_id);
CREATE INDEX idx_stock_reservation_tenant ON stock_reservation (tenant_id);
