-- THE DELIVERY LEDGER: a finished bill's trip to the distribution
-- partner, written in the SAME transaction that cuts the bill (the
-- outbox property: if the bill exists, the delivery intent exists).
-- A relay drains pending rows with exponential backoff; every row says
-- what left, when, after how many tries — "we POSTed it" becomes
-- "we can prove what happened to it".
CREATE TABLE bill_distribution (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    bill_id         VARCHAR(36) NOT NULL,
    bill_no         VARCHAR(64) NOT NULL,
    format          VARCHAR(16) NOT NULL,
    channel         VARCHAR(16) NOT NULL,
    recipient       VARCHAR(64),
    content_type    VARCHAR(64) NOT NULL,
    payload         VARCHAR(500000) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempts        INT NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE,
    last_update     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_distribution_due ON bill_distribution (tenant_id, status, next_attempt_at);
CREATE INDEX idx_distribution_bill ON bill_distribution (tenant_id, bill_no);
