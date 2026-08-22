-- G1 — a data reward that arrives BEFORE the joiner's meter exists is
-- PARKED, never lost: it lands the moment their first GB record opens a
-- bucket. reward_id is the idempotency key end to end.
CREATE TABLE pending_data_reward (
    reward_id  VARCHAR(80)  PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    party_id   VARCHAR(64)  NOT NULL,
    gb         NUMERIC(6,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_pending_reward_party ON pending_data_reward (tenant_id, party_id);
