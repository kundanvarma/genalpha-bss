-- JOURNEYS: sequences as data. A journey is a trigger (event or insight
-- segment) plus ordered steps (message | wait) and a conversion event that
-- doubles as the ALWAYS-ON EXIT RULE — a converter leaves from any step,
-- so nobody gets "10% off!" the day after they paid full price. Guardrails
-- live in the model: once-per-customer enrollment, holdouts for lift.
CREATE TABLE journey (
    id                  VARCHAR(36) PRIMARY KEY,
    href                VARCHAR(255),
    tenant_id           VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name                VARCHAR(255) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    trigger_event_type  VARCHAR(128),
    trigger_state       VARCHAR(64),
    segment_name        VARCHAR(128),
    -- ordered steps as JSON: [{"type":"message","subject":...,"content":...,
    --  "promotionCode":...}, {"type":"wait","seconds"|"minutes"|"hours"|"days":N}]
    steps               VARCHAR(4000) NOT NULL,
    conversion_event    VARCHAR(128),
    holdout_percent     INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update         TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_journey_tenant ON journey (tenant_id, status);

CREATE TABLE journey_enrollment (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    journey_id     VARCHAR(36) NOT NULL,
    party_id       VARCHAR(64) NOT NULL,
    variant        VARCHAR(16) NOT NULL DEFAULT 'treated',
    -- active | converted | completed
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    step_index     INT NOT NULL DEFAULT 0,
    next_action_at TIMESTAMP WITH TIME ZONE,
    enrolled_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    converted_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_enrollment UNIQUE (tenant_id, journey_id, party_id)
);
CREATE INDEX idx_enrollment_due ON journey_enrollment (tenant_id, status, next_action_at);
