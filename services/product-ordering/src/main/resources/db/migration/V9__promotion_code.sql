-- TMF671 seam: the code a customer applied at checkout; redeemed into a
-- discount when the order completes. (V8 is the postgres-only RLS migration.)
ALTER TABLE product_order ADD COLUMN promo_code VARCHAR(64);
