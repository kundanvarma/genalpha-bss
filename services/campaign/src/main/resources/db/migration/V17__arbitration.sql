-- BB4: real-time next-best-action arbitration. A journey carries a priority
-- (the human-set policy); when two would message the same customer in the
-- same tick, the higher priority wins and every decision is logged.
ALTER TABLE journey ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;

CREATE TABLE arbitration_decision (
    id                VARCHAR(36)  NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL,
    party_id          VARCHAR(64)  NOT NULL,
    winner_journey_id VARCHAR(36),
    held_journey_id   VARCHAR(36),
    reason            VARCHAR(500),
    decided_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_arbitration_decision PRIMARY KEY (id)
);
CREATE INDEX idx_arbitration_tenant_party ON arbitration_decision (tenant_id, party_id);
