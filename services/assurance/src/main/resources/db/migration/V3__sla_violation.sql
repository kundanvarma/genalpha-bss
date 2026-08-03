-- TMF623: SLA violations as a LEDGER. Terms live on the agreement (data);
-- a problem that resolves past its promised time mints a violation here,
-- events it, and billing compensates with a PRE-AGREED credit note.
-- The cap is enforced against this ledger, per agreement per month.
CREATE TABLE sla_violation (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    agreement_id     VARCHAR(36) NOT NULL,
    party_id         VARCHAR(64),
    problem_id       VARCHAR(36) NOT NULL,
    affected_object  VARCHAR(128),
    threshold_minutes BIGINT,
    duration_minutes  BIGINT,
    credit_amount    NUMERIC(12,2),
    credited         BOOLEAN NOT NULL DEFAULT TRUE,
    note             VARCHAR(512),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_sla_violation_problem ON sla_violation (tenant_id, agreement_id, problem_id);
CREATE INDEX idx_sla_violation_agreement ON sla_violation (tenant_id, agreement_id);
