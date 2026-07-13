-- Split billing: how much of a device's monthly charge the company covers
-- for its people. NULL = no policy, the company pays devices in full.
ALTER TABLE organization ADD COLUMN device_allowance_value NUMERIC(12,2);
ALTER TABLE organization ADD COLUMN device_allowance_unit VARCHAR(8);
