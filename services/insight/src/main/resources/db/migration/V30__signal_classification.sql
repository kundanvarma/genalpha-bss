-- The classification battery's store (SI-P3): one row per signal, written by
-- the intelligence sweep. Every classified field carries an EVIDENCE quote
-- and insight verifies each quote VERBATIM against the signal text before
-- accepting the row — a classification that cannot cite its source is
-- dropped at the door, not shown. Provider+model ride the row (the same
-- honesty as the AI audit ledger).
CREATE TABLE signal_classification (
    id                VARCHAR(36) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    signal_id         VARCHAR(36) NOT NULL,
    sentiment         VARCHAR(16),
    aspect            VARCHAR(32),
    category          VARCHAR(32),
    pain_point        VARCHAR(500),
    pain_impact       INTEGER,
    loyalty_indicator VARCHAR(16),
    churn_signal      BOOLEAN NOT NULL DEFAULT FALSE,
    churn_reason      VARCHAR(255),
    evidence          VARCHAR(2000),
    provider          VARCHAR(64),
    model             VARCHAR(64),
    classified_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_signal_classification PRIMARY KEY (id),
    CONSTRAINT uq_signal_classification UNIQUE (tenant_id, signal_id)
);
CREATE INDEX idx_signal_classification_tenant ON signal_classification (tenant_id, classified_at);
