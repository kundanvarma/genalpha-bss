-- TMF679 v3-style face: productOfferingQualification as a TASK document,
-- posted by R18-era clients, evaluated best-effort by the same engine the
-- v4 check uses, and kept.
CREATE TABLE legacy_poq (
    id            VARCHAR(36) PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    state         VARCHAR(32) NOT NULL,
    document_json VARCHAR(8000),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
