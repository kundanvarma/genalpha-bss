-- DECISION LOG (continuous learning, phase 1): one row per adaptive choice any
-- service made — the context it saw, the actions it could take, the one it
-- took, the policy and version, the propensity — and, when it arrives, the
-- outcome that followed. The receipt an auditor reads and the replay a
-- future policy is judged on both come from here.
CREATE TABLE decision_log (
    id              VARCHAR(64) PRIMARY KEY,                -- the decision id the source row keeps
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    decision_point  VARCHAR(64) NOT NULL,                   -- journey.enrolment, campaign.treatment, ...
    subject_type    VARCHAR(32),                            -- party | journey | offering | desk
    subject_id      VARCHAR(64),
    candidates      TEXT,                                   -- JSON list, before constraints
    eligible        TEXT,                                   -- JSON list, after constraints
    constraints     TEXT,                                   -- JSON list of "rule: action — why"
    action          VARCHAR(120),
    propensity      NUMERIC(7,4),                           -- null = deterministic policy
    policy          VARCHAR(64) NOT NULL,
    policy_version  VARCHAR(16) NOT NULL,
    reason          VARCHAR(1000),
    context         TEXT,                                   -- JSON, identifiers and numbers only
    evidence        TEXT,                                   -- JSON, the numbers the policy produced
    autonomy        VARCHAR(8),                             -- high | medium | low
    fallback        BOOLEAN NOT NULL DEFAULT FALSE,
    source          VARCHAR(40) NOT NULL,                   -- the service that decided
    decided_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome         VARCHAR(40),                            -- conversion | adopted | dismissed | ...
    outcome_value   NUMERIC(14,2),
    outcome_at      TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_decision_log_point ON decision_log (tenant_id, decision_point, decided_at DESC);
CREATE INDEX idx_decision_log_subject ON decision_log (tenant_id, subject_id);
