-- Network slicing (5G SA): a line may ride a priority slice profile — sold as a
-- plan tier, a monthly add-on, or a time-boxed BOOST PASS. The service record
-- carries the profile and its expiry; the core enforces, the BSS reconciles.
ALTER TABLE service ADD COLUMN slice_profile VARCHAR(64);
ALTER TABLE service ADD COLUMN slice_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE service ADD COLUMN slice_order_id VARCHAR(36);
CREATE INDEX idx_service_slice_until ON service (slice_until);
