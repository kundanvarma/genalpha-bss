-- The carrier that shipped each parcel, persisted from the logistics seam's
-- booking response — so the shop shows whoever actually carried it, not a
-- hardcoded name. Null until a parcel is booked (or the seam is off).
ALTER TABLE shipping_order ADD COLUMN carrier VARCHAR(64);
