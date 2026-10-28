-- Partner invoices (#390): what a partner says it is owed for a month, set
-- beside what the ledger recorded as owed, and when the operator paid it. One
-- invoice per partner, month and the partner's own reference, so recording the
-- same invoice twice is still one invoice. Amounts are money.
CREATE TABLE partner_invoice (
    tenant_id    VARCHAR(64)    NOT NULL,
    id           VARCHAR(36)    NOT NULL,
    partner      VARCHAR(255)   NOT NULL,
    period       VARCHAR(7)     NOT NULL,
    reference    VARCHAR(120)   NOT NULL,
    amount       NUMERIC(12, 2) NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    recorded_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_partner_invoice PRIMARY KEY (id),
    CONSTRAINT uq_partner_invoice UNIQUE (tenant_id, partner, period, reference),
    CONSTRAINT ck_partner_invoice_amount CHECK (amount >= 0)
);
CREATE INDEX ix_partner_invoice_period ON partner_invoice (tenant_id, period);
