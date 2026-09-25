-- Catalog-to-provisioning step 2: the orchestrator RECORDS which resource-facing
-- services it realised for a service — through which seam, by which vendor,
-- with what external reference — and whether the product spec's CFS declared
-- that RFS (rfs_id null = the code did something the catalog never said).
-- Descriptive: nothing reads this table to decide fulfilment yet.
CREATE TABLE service_realisation (
    tenant_id          VARCHAR(64)  NOT NULL,
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    service_id         VARCHAR(36)  NOT NULL,
    rfs_id             VARCHAR(64),
    rfs_name           VARCHAR(160),
    seam               VARCHAR(32)  NOT NULL,
    vendor             VARCHAR(64),
    external_ref       VARCHAR(160),
    resource_spec_id   VARCHAR(64),
    resource_spec_name VARCHAR(160),
    realised_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_realisation_service ON service_realisation (tenant_id, service_id);
