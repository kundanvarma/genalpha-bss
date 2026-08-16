-- O2 lead scoring + routing.

-- The lead carries its score, grade and the owner it routed to; company size
-- is a scoring signal for B2B.
ALTER TABLE sales_lead ADD COLUMN score        INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sales_lead ADD COLUMN grade        VARCHAR(8);
ALTER TABLE sales_lead ADD COLUMN owner_id     VARCHAR(64);
ALTER TABLE sales_lead ADD COLUMN owner_name   VARCHAR(255);
ALTER TABLE sales_lead ADD COLUMN company_size INTEGER;

-- Scoring rules: signal → points. field ∈ source | companyPresent |
-- companySizeMin | keyword.
CREATE TABLE lead_scoring_rule (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    field        VARCHAR(24)  NOT NULL,
    signal_value VARCHAR(255),   -- 'value' is a reserved word in H2 (unit tests)
    points       INTEGER      NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_lead_scoring_rule ON lead_scoring_rule (tenant_id);

-- Routing rules: a lead scoring at or above min_score routes to the assignee
-- of the highest band it clears.
CREATE TABLE lead_routing_rule (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    min_score  INTEGER      NOT NULL,
    assignee   VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_lead_routing_rule ON lead_routing_rule (tenant_id, min_score);
