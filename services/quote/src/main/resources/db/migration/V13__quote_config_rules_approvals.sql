-- CPQ C2: configuration rules + discount approvals.

-- Configuration rules the quote builder enforces (and an agent can pre-check
-- via the validate endpoint): requires / excludes / min / max on offerings.
CREATE TABLE quote_config_rule (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    rule_type        VARCHAR(16)  NOT NULL,   -- requires | excludes | minQty | maxQty
    subject_offering VARCHAR(255) NOT NULL,   -- the offering the rule is about
    object_offering  VARCHAR(255),            -- the other offering (requires/excludes)
    qty              INTEGER,                  -- the bound (minQty/maxQty)
    message          VARCHAR(500) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_config_rule ON quote_config_rule (tenant_id, subject_offering);

-- A quote-level discount and its approval gate: a discount over the threshold
-- is 'pending' until a manager approves it, and the quote can't be approved/
-- accepted while pending (the human gate on an agent-proposed discount).
ALTER TABLE quote ADD COLUMN discount_percent NUMERIC(5,2) NOT NULL DEFAULT 0;
ALTER TABLE quote ADD COLUMN approval_status  VARCHAR(16)  NOT NULL DEFAULT 'notRequired';
