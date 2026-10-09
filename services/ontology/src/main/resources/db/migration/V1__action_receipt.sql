-- THE ACTION RECEIPT STORE (ADR 0025).
--
-- Until now a governed action's receipt was built after the action returned and
-- handed to Kafka asynchronously. A send failure reached a counter and an ERROR
-- line while the action had already happened, so evidence could silently not
-- exist. Ontology had no database, so there was nowhere to record intent first.
--
-- Two rows per action, never one that mutates:
--
--   phase='attempt'  written and COMMITTED BEFORE the call is dispatched. If it
--                    cannot commit, the action is refused and never dispatched.
--   phase='outcome'  appended after the call returns, linked by receipt_id. A
--                    failed outcome write must NOT fail the caller — the action
--                    already happened — so the attempt simply stays unresolved
--                    and the reconciler owns it.
--
-- Append-only by rule: nothing updates a row. "What happened next" is always a
-- new row, which is what makes the later hash chain meaningful.

CREATE TABLE action_receipt (
    id              VARCHAR(64) PRIMARY KEY,         -- this row
    receipt_id      VARCHAR(64) NOT NULL,            -- the action; attempt and outcome share it
    phase           VARCHAR(16) NOT NULL,            -- attempt | outcome
    tenant_id       VARCHAR(64) NOT NULL,

    -- who, and under what authority
    principal       VARCHAR(200),                    -- the human or service subject
    agent           VARCHAR(120),                    -- registered agent id, when an agent acted
    agent_version   VARCHAR(40),
    authority       TEXT,                            -- delegated scope the caller held; a staff
                                                     -- token carries dozens of roles, so this is
                                                     -- not a short string (VARCHAR(400) overflowed)

    -- what was intended
    action          VARCHAR(120) NOT NULL,
    action_version  VARCHAR(40),
    inputs          TEXT,                            -- JSON, resolved inputs as sent
    check_verdict   TEXT,                            -- JSON, the Check that allowed it
    target          VARCHAR(400),                    -- component / capability / route
    idempotency_key VARCHAR(64),
    attempt_no      INTEGER     NOT NULL DEFAULT 1,

    -- what happened (outcome rows only)
    status          VARCHAR(16),                     -- succeeded | failed | uncertain | refused
    component_status INTEGER,
    provider_ref    VARCHAR(200),                    -- the far side's own identifier
    effects         TEXT,                            -- JSON
    emits           TEXT,                            -- JSON
    refusal         VARCHAR(1000),
    compensation_ref VARCHAR(200),

    -- the chain (ADR 0025); populated per tenant in receipt order
    prev_hash       VARCHAR(64),
    row_hash        VARCHAR(64),

    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_action_receipt_receipt ON action_receipt (tenant_id, receipt_id);
CREATE INDEX idx_action_receipt_time    ON action_receipt (tenant_id, recorded_at DESC);
-- the reconciler's query: attempts with no outcome row yet
CREATE INDEX idx_action_receipt_phase   ON action_receipt (tenant_id, phase, recorded_at);
-- a retry of the same intent is findable without scanning
CREATE INDEX idx_action_receipt_idem    ON action_receipt (tenant_id, idempotency_key);

-- NOT HERE YET: the transactional outbox (ADR 0004, ADR 0025). Publication to
-- insight still goes straight to Kafka from ReceiptPublisher, so a publish can
-- still be lost even though the receipt itself can no longer be. That is a
-- smaller hole than the one this migration closes — the durable record is now
-- local and complete — but it is a hole, and an empty outbox table sitting here
-- unused would claim otherwise. It arrives with the relay or not at all.
