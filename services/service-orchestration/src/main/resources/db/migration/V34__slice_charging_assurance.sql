-- Slice-aware charging + a sold guarantee + device eligibility on the line.
ALTER TABLE service ADD COLUMN slice_base_charging_spec VARCHAR(64);
ALTER TABLE service ADD COLUMN slice_guaranteed_dl_mbps INTEGER;
ALTER TABLE service ADD COLUMN device_model VARCHAR(128);
