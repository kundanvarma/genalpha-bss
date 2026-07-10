-- Customer channels need "my products": a queryable owner column, derived from
-- the relatedParty payload (JSON is stored verbatim and cannot be filtered).

ALTER TABLE product ADD COLUMN owner_party_id VARCHAR(36);
CREATE INDEX idx_product_owner ON product (owner_party_id);
