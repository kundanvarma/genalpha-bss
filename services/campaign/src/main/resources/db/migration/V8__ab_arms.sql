-- A/B arms: optional message variants on a campaign; treated customers
-- split deterministically across arms and the execution remembers which
-- arm spoke, so conversions read per arm.
ALTER TABLE campaign ADD COLUMN arms VARCHAR(4000);
ALTER TABLE campaign_execution ADD COLUMN arm VARCHAR(32);
