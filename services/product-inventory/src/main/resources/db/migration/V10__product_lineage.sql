-- TMF637 lineage: which order item made the product, and which service realises
-- it. Until now the desk paired products and services by name; twin lines were
-- ambiguous. The realizing service id is indexed because terminate/transfer
-- events are correlated on it first, by name only as the fallback.
ALTER TABLE product ADD COLUMN product_order_item VARCHAR(2000);
ALTER TABLE product ADD COLUMN realizing_service VARCHAR(2000);
ALTER TABLE product ADD COLUMN realizing_service_id VARCHAR(36);
CREATE INDEX idx_product_realizing_service ON product (tenant_id, realizing_service_id);
