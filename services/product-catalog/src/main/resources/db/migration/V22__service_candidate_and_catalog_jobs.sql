-- TMF633 Service Catalog, the rest of the resource set the standard names:
--   serviceCandidate  a ServiceSpecification made available for ordering under a
--                     category — the service-side twin of a ProductOffering
--   importJob         a request to load specs from a URL (a job record; no runner
--                     yet picks it up — status stays "Not Started")
--   exportJob         the mirror: a request to write the catalog to a URL
-- Both jobs share one table with a kind column: same lifecycle, same shape.

CREATE TABLE service_candidate (
    id                    VARCHAR(36)  NOT NULL,
    tenant_id             VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    href                  VARCHAR(255),
    name                  VARCHAR(255) NOT NULL,
    description           VARCHAR(1024),
    version               VARCHAR(64),
    lifecycle_status      VARCHAR(64),
    valid_from            TIMESTAMP WITH TIME ZONE,
    valid_to              TIMESTAMP WITH TIME ZONE,
    category              VARCHAR(4000),
    service_specification VARCHAR(2000),
    last_update           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_service_candidate PRIMARY KEY (id)
);

CREATE INDEX idx_service_candidate_tenant ON service_candidate (tenant_id);

CREATE TABLE service_catalog_job (
    id              VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    job_kind        VARCHAR(16)  NOT NULL,
    href            VARCHAR(255),
    url             VARCHAR(1024) NOT NULL,
    path            VARCHAR(1024),
    content_type    VARCHAR(255),
    query           VARCHAR(2000),
    status          VARCHAR(32),
    error_log       VARCHAR(4000),
    creation_date   TIMESTAMP WITH TIME ZONE,
    completion_date TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_service_catalog_job PRIMARY KEY (id)
);

CREATE INDEX idx_service_catalog_job_tenant ON service_catalog_job (tenant_id, job_kind);

-- isBundle is mandatory on the wire (a boolean, never absent): rows authored
-- before the flag was enforced read as "not a bundle".
UPDATE service_specification SET is_bundle = FALSE WHERE is_bundle IS NULL;
