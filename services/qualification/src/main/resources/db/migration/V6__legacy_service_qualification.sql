-- TMF645 v3 face: qualification tasks as R18-era clients file them — the
-- full posted document kept, the evaluation stamped on each item. Rides
-- beside the v4 check store, never in its way.
CREATE TABLE legacy_service_qualification (
    id            VARCHAR(36) PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    external_id   VARCHAR(64),
    state         VARCHAR(32) NOT NULL,
    document_json VARCHAR(8000),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_legacy_sq_external ON legacy_service_qualification (tenant_id, external_id);
