-- LEARNING CONTRACTS (continuous learning, phase 4): per tenant and per
-- DecisionPoint, the intent as configuration — what to optimise, what must
-- hold, which actions are allowed, how much may be explored, how much
-- autonomy the point has, what answers when the policy cannot. The seam reads
-- it on every decision; the decision record names the contract version it ran under.
CREATE TABLE learning_contract (
    id                      VARCHAR(36) PRIMARY KEY,
    tenant_id               VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    decision_point          VARCHAR(64) NOT NULL,
    objective               VARCHAR(40),            -- the outcome the point is optimised for, e.g. conversion
    secondary_metrics       VARCHAR(500),           -- JSON list of outcome/metric names that must not degrade
    guardrails              VARCHAR(1000),          -- JSON list of hard rules, in words (consent, statute, brand)
    allowed_actions         VARCHAR(1000),          -- JSON list; null = every candidate
    exploration_max_percent INTEGER,                -- cap on customers deliberately left silent (holdout); null = no cap
    autonomy                VARCHAR(8),             -- high | medium | low; null = the point's default
    fallback_action         VARCHAR(120),           -- null = the caller's default
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,   -- false = the policy is paused: fallback answers every time
    notes                   VARCHAR(1000),
    version                 INTEGER NOT NULL DEFAULT 1,
    updated_by              VARCHAR(120),
    last_update             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_learning_contract UNIQUE (tenant_id, decision_point)
);
