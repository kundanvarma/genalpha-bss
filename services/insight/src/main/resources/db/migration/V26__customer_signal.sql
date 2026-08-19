-- The signal store (signal-intelligence SI-P1): one entity for everything a
-- customer SAYS, whatever the source — tickets, reviews, chats, calls, CRM
-- notes. Text is stored REDACTED ONLY (the PII firewall runs at ingest; raw
-- text is never persisted) and `redactions` records what was removed
-- (types + counts, never values). Pseudonymized is NOT anonymized: the row
-- remains personal data — RLS below, erasure by party_id in the privacy
-- corner. Idempotent per (tenant, dedup_hash).
CREATE TABLE customer_signal (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    source      VARCHAR(32)  NOT NULL,
    source_ref  VARCHAR(255),
    party_id    VARCHAR(64),
    channel     VARCHAR(32),
    lang        VARCHAR(8),
    text        VARCHAR(4000) NOT NULL,
    context     VARCHAR(2000),
    redactions  VARCHAR(500),
    dedup_hash  VARCHAR(64)  NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_customer_signal PRIMARY KEY (id),
    CONSTRAINT uq_customer_signal UNIQUE (tenant_id, dedup_hash)
);
CREATE INDEX idx_customer_signal_tenant ON customer_signal (tenant_id, received_at);
CREATE INDEX idx_customer_signal_party ON customer_signal (tenant_id, party_id);
