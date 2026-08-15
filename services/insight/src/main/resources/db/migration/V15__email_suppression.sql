-- DNC ledger, projected from communication's EmailSuppressedEvent — so an
-- ad-platform export can filter opted-out/bounced addresses with a local lookup
-- (no cross-service call on the hot path). Idempotent per (tenant, email).
CREATE TABLE email_suppression (
    id         VARCHAR(36)  NOT NULL,
    tenant_id  VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    email      VARCHAR(255) NOT NULL,
    reason     VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_email_suppression PRIMARY KEY (id),
    CONSTRAINT uq_email_suppression UNIQUE (tenant_id, email)
);
CREATE INDEX idx_email_suppression_tenant ON email_suppression (tenant_id);
