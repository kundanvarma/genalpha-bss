-- DISPUTES: a customer contests a charge. One OPEN dispute per bill;
-- collection pauses while it is open; resolution is a CREDIT (money off
-- an unpaid bill, or a real refund on a settled one) or an UPHOLD with
-- the reason written down.
CREATE TABLE bill_dispute (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    bill_id         VARCHAR(36) NOT NULL,
    party_id        VARCHAR(64),
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    credit_amount   NUMERIC(12,2),
    resolution_note VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at     TIMESTAMP WITH TIME ZONE,
    last_update     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_dispute_bill ON bill_dispute (tenant_id, bill_id);
