-- UNAPPLIED CASH: money the bank says arrived but no bill claims — an
-- unknown reference, a wrong amount, an already-settled bill. Classic
-- accounts-receivable: it is never dropped, it goes on a worklist a
-- human resolves. Fail-closed is the rule for money: when in doubt,
-- park it and say so.
CREATE TABLE unapplied_remittance (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    batch_ref    VARCHAR(64),
    reference    VARCHAR(64),
    amount_value NUMERIC(12,2) NOT NULL,
    amount_unit  VARCHAR(8) NOT NULL,
    reason       VARCHAR(255) NOT NULL,
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_unapplied_tenant ON unapplied_remittance (tenant_id, received_at);
