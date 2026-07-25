-- TMF658 loyalty: the program (per-tenant DATA, editable like policy),
-- the member (opt-in, never auto), and the append-only journal — every
-- point movement carries its cause, because points are a LIABILITY.
CREATE TABLE loyalty_program (
    tenant_id VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    earn_points_per_currency NUMERIC(10,4) NOT NULL DEFAULT 1,
    points_per_gb INTEGER NOT NULL DEFAULT 100,
    last_update TIMESTAMP WITH TIME ZONE
);

CREATE TABLE loyalty_member (
    id VARCHAR(64) NOT NULL,              -- the party id
    tenant_id VARCHAR(64) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    enrolled_at TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id, tenant_id)
);

CREATE TABLE loyalty_transaction (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    party_id VARCHAR(64) NOT NULL,
    tx_type VARCHAR(16) NOT NULL,         -- earn | burn
    points BIGINT NOT NULL,               -- positive earn, negative burn
    cause VARCHAR(200) NOT NULL,          -- bill:<id> | redeem:data:<gb>GB:<redemptionId>
    created_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_loyalty_tx_party ON loyalty_transaction (tenant_id, party_id);
CREATE UNIQUE INDEX idx_loyalty_tx_cause ON loyalty_transaction (tenant_id, cause);

-- The transactional outbox (exact shape of the shared OutboxEvent entity).
CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
