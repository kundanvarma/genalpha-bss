-- The shopper's delivery choice on the parcel (C-P3): home vs a named pickup
-- point / locker. Null delivery_method = home (the default), so old rows are fine.
ALTER TABLE shipping_order ADD COLUMN delivery_method VARCHAR(24);
ALTER TABLE shipping_order ADD COLUMN pickup_point VARCHAR(128);
