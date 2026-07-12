-- TMF687 CTK conformance. ProductStock: store the posted body so spec fields
-- (productStockLevel, productStockStatusType, stockedProduct) round-trip on GET,
-- and make name optional (the spec doesn't require it).
ALTER TABLE product_stock ADD COLUMN payload_json VARCHAR(8000);
ALTER TABLE product_stock ALTER COLUMN name DROP NOT NULL;

-- ReserveProductStock as a first-class, queryable resource (the spec models it
-- as a resource with CRUD; internally the app still uses the task endpoints and
-- stock_reservation for order-lifecycle reservations — this table is the
-- spec-facing resource, kept separate so the two never interfere).
CREATE TABLE reserve_product_stock (
    id           VARCHAR(36) PRIMARY KEY,
    href         VARCHAR(255),
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    state        VARCHAR(32),
    payload_json VARCHAR(8000),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_reserve_product_stock_tenant ON reserve_product_stock (tenant_id);
