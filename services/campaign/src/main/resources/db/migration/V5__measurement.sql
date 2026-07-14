-- MEASUREMENT: the difference between a marketing tool and a message
-- cannon. A campaign may hold out a random N% (the control group — same
-- segment, same ledger, no message), names its conversion event and a
-- conversion window; executions record which variant a customer was in
-- and when (whether) they converted. Lift = treated rate - holdout rate.
ALTER TABLE campaign ADD COLUMN conversion_event VARCHAR(128);
ALTER TABLE campaign ADD COLUMN conversion_window_days INT NOT NULL DEFAULT 7;
ALTER TABLE campaign ADD COLUMN holdout_percent INT NOT NULL DEFAULT 0;
ALTER TABLE campaign_execution ADD COLUMN variant VARCHAR(16) NOT NULL DEFAULT 'treated';
ALTER TABLE campaign_execution ADD COLUMN converted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE campaign_execution ADD COLUMN conversion_ref VARCHAR(64);
CREATE INDEX idx_execution_party_open ON campaign_execution (tenant_id, party_id) WHERE converted_at IS NULL;
