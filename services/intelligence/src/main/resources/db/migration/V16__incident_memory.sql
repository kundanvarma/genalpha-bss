-- Episodic memory for the process incident agent: every investigation is
-- a TRACE — signature, assembled-context digest, hypothesis, confidence,
-- the MANDATORY human verdict, and how long diagnosis took. Traces are
-- what runbooks are later promoted from (P3); nothing is deleted.
CREATE TABLE incident_trace (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    signature        VARCHAR(160) NOT NULL,
    process_flow_id  VARCHAR(36) NOT NULL,
    product_order_id VARCHAR(64),
    spec_code        VARCHAR(64),
    task_code        VARCHAR(64),
    party_id         VARCHAR(64),
    context_digest   VARCHAR(4000),
    hypothesis       VARCHAR(2000),
    confidence       NUMERIC(4,3),
    proposed_action  VARCHAR(1000),
    source           VARCHAR(16) NOT NULL,
    ticket_id        VARCHAR(36),
    verdict          VARCHAR(16) NOT NULL DEFAULT 'pending',
    verdict_note     VARCHAR(512),
    diagnose_ms      BIGINT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_incident_flow ON incident_trace (tenant_id, process_flow_id);
CREATE INDEX idx_incident_signature ON incident_trace (tenant_id, signature);
