-- The opportunity grows from a bare TMF699 stub into a real B2B pipeline
-- object: a value, a close date, a stage on the board, a probability, an
-- owner, and (when the deal is with an account we already know) the party
-- it is for. This is what a sales manager forecasts from.
ALTER TABLE sales_opportunity ADD COLUMN amount             NUMERIC(14,2);
ALTER TABLE sales_opportunity ADD COLUMN currency           VARCHAR(3);
ALTER TABLE sales_opportunity ADD COLUMN expected_close_date DATE;
ALTER TABLE sales_opportunity ADD COLUMN stage              VARCHAR(32);
ALTER TABLE sales_opportunity ADD COLUMN probability        INTEGER;
ALTER TABLE sales_opportunity ADD COLUMN owner_id           VARCHAR(64);
ALTER TABLE sales_opportunity ADD COLUMN owner_name         VARCHAR(255);
ALTER TABLE sales_opportunity ADD COLUMN close_reason       VARCHAR(255);
ALTER TABLE sales_opportunity ADD COLUMN party_id           VARCHAR(64);

-- Opportunity line items: the composition of the deal, each item a product
-- offering from the TMF620 catalog. The opportunity's amount is the sum of
-- these when any exist (else a manually-set number).
CREATE TABLE opportunity_item (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    opportunity_id VARCHAR(36)  NOT NULL,
    offering_id    VARCHAR(64),
    offering_name  VARCHAR(255) NOT NULL,
    quantity       INTEGER      NOT NULL DEFAULT 1,
    unit_price     NUMERIC(14,2) NOT NULL DEFAULT 0,
    currency       VARCHAR(3),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_opp_item ON opportunity_item (tenant_id, opportunity_id);

-- Opportunity activities: the sales workspace — every call, email, note and
-- lifecycle beat. When the deal is with a known party these mirror onto the
-- customer's TMF683 360 timeline (via bss.quote.events), so a CSR sees sales
-- and service on one record.
CREATE TABLE opportunity_activity (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    opportunity_id VARCHAR(36)  NOT NULL,
    party_id       VARCHAR(64),
    activity_type  VARCHAR(32)  NOT NULL,
    note           VARCHAR(2000) NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_opp_activity ON opportunity_activity (tenant_id, opportunity_id, occurred_at);
