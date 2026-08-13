-- Async open access: the callback from the owner's OSS completes THIS retail order
-- item, so store which item the wholesale access realizes.
ALTER TABLE wholesale_access_order ADD COLUMN order_item_id VARCHAR(64);
