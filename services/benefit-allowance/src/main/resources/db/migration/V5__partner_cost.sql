-- Partner cost (#360). The partner sells, the operator pays: each benefit's
-- partner and expected monthly wholesale cost are snapshotted on the
-- allocation when it is picked (a later catalog change does not rewrite what
-- was agreed), and one row per active benefit per month records what the
-- operator owes. The (allocation, period) key makes a replay or a duplicate
-- partner callback a no-op. Amounts are money; Choices never are.
ALTER TABLE choice_allocation ADD COLUMN IF NOT EXISTS partner VARCHAR(255);
ALTER TABLE choice_allocation ADD COLUMN IF NOT EXISTS wholesale_cost NUMERIC(12, 2);

CREATE TABLE partner_cost (
    tenant_id      VARCHAR(64)   NOT NULL,
    id             VARCHAR(36)   NOT NULL,
    allocation_id  VARCHAR(36)   NOT NULL,
    period         VARCHAR(7)    NOT NULL,
    partner        VARCHAR(255)  NOT NULL,
    offering_name  VARCHAR(255)  NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    recorded_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_partner_cost PRIMARY KEY (id),
    CONSTRAINT uq_partner_cost_month UNIQUE (tenant_id, allocation_id, period),
    CONSTRAINT ck_partner_cost_amount CHECK (amount >= 0)
);
CREATE INDEX ix_partner_cost_period ON partner_cost (tenant_id, period);
