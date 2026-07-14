-- Rollover boosts use a synthetic idempotency marker
-- ('rollover-<period>-<party>-<spec>') that outgrows a bare order id.
ALTER TABLE allowance_boost ALTER COLUMN product_order_id TYPE VARCHAR(160);
