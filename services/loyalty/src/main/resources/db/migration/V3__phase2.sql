-- Phase 2: tiers, vouchers, expiry — the program grows levers, the member
-- grows a tier, and the fleet-safe tick lock arrives for the expiry sweep.
ALTER TABLE loyalty_program ADD COLUMN expiry_months INTEGER NOT NULL DEFAULT 0;
ALTER TABLE loyalty_program ADD COLUMN voucher_percent INTEGER NOT NULL DEFAULT 10;
ALTER TABLE loyalty_program ADD COLUMN points_per_voucher INTEGER NOT NULL DEFAULT 200;
ALTER TABLE loyalty_program ADD COLUMN silver_threshold BIGINT NOT NULL DEFAULT 500;
ALTER TABLE loyalty_program ADD COLUMN gold_threshold BIGINT NOT NULL DEFAULT 2000;
ALTER TABLE loyalty_member ADD COLUMN tier VARCHAR(16) NOT NULL DEFAULT 'bronze';

CREATE TABLE tick_lock (
    name         VARCHAR(80) PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(120)             NOT NULL
);
