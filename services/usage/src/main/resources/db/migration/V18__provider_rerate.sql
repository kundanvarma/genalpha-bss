-- W-M7 closed loop: the mediation feed's CORRECTED re-report re-rates the
-- provider ledger row in place — the receipt of every correction on the row.
ALTER TABLE provider_usage_ledger ADD COLUMN rerate_count INT NOT NULL DEFAULT 0;
ALTER TABLE provider_usage_ledger ADD COLUMN last_rerated_at TIMESTAMP WITH TIME ZONE;
