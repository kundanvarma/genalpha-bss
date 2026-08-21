-- CLOSED-LOOP LATE-CDR RE-RATING: a ledger row whose CDRs kept arriving
-- after rating is no longer just FLAGGED unreconciled — the loop re-rates
-- it and revenue books the delta. These columns are the receipt: how many
-- times the row moved, and when it last did.
ALTER TABLE wholesale_usage_ledger ADD COLUMN rerate_count INT NOT NULL DEFAULT 0;
ALTER TABLE wholesale_usage_ledger ADD COLUMN last_rerated_at TIMESTAMP WITH TIME ZONE;
