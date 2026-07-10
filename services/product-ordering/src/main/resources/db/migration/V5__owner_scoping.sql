-- Customer channels need "my orders": a queryable owner column, derived from
-- the relatedParty payload (JSON is stored verbatim and cannot be filtered).

ALTER TABLE product_order ADD COLUMN owner_party_id VARCHAR(36);
CREATE INDEX idx_product_order_owner ON product_order (owner_party_id);
