-- Pay in parts: an outstanding bill splits into N equal monthly
-- installments (last takes the rounding remainder). One plan per bill;
-- the bill reads partiallyPaid while the plan runs and settles itself
-- when the last part lands.
CREATE TABLE installment_plan (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    bill_id      VARCHAR(36) NOT NULL,
    installments INT NOT NULL,
    amount_per   NUMERIC(12,2) NOT NULL,
    currency     VARCHAR(8) NOT NULL,
    paid_count   INT NOT NULL DEFAULT 0,
    status       VARCHAR(16) NOT NULL,
    next_due_at  TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_plan_bill UNIQUE (tenant_id, bill_id)
);
