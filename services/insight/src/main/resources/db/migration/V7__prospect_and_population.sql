-- Prospects: not-yet-customers the marketing team may nurture (captured leads,
-- list imports, social lead-forms). Consent is the spine — a bought list lands
-- as 'unconsented' and is captured but NOT reachable until a lawful basis is
-- recorded. Keyed by email per tenant so re-imports are idempotent.
CREATE TABLE prospect (
    id           VARCHAR(36)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    email        VARCHAR(255),
    phone        VARCHAR(64),
    name         VARCHAR(255),
    source       VARCHAR(128),
    consent      VARCHAR(32)  NOT NULL DEFAULT 'unconsented',
    lawful_basis VARCHAR(64),
    social_ref   VARCHAR(128),
    created_at   TIMESTAMP WITH TIME ZONE,
    updated_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_prospect PRIMARY KEY (id),
    CONSTRAINT uq_prospect_email UNIQUE (tenant_id, email)
);
CREATE INDEX idx_prospect_tenant ON prospect (tenant_id);
CREATE INDEX idx_prospect_source ON prospect (tenant_id, source);

-- An audience targets one POPULATION: the operator's customers (default) or its
-- prospects. Same rule-tree engine, two candidate bases.
ALTER TABLE audience ADD COLUMN population VARCHAR(32) NOT NULL DEFAULT 'customer';
