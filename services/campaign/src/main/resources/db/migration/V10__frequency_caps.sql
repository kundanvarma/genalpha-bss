-- FREQUENCY CAPS: Braze's own retrospective — teams forgot guardrails
-- when they were optional, so the guardrail lives in the model. A tenant
-- sets a marketing-touch budget (max messages per N days, 0 = off); every
-- actual marketing send is a touch; a customer at their budget is left
-- alone by campaigns and journeys alike.
CREATE TABLE martech_setting (
    tenant_id              VARCHAR(64) NOT NULL PRIMARY KEY,
    max_marketing_messages INT         NOT NULL DEFAULT 0,
    per_days               INT         NOT NULL DEFAULT 1,
    last_update            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE marketing_touch (
    id        VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    party_id  VARCHAR(64) NOT NULL,
    source    VARCHAR(16) NOT NULL,
    sent_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_touch_party ON marketing_touch (tenant_id, party_id, sent_at);
