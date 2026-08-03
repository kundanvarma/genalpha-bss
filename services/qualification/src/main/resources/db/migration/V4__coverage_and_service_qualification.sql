-- TMF645 Service Qualification: the TECHNICAL question TMF679 never asked.
-- coverage_map is the operator's footprint as data — which access technology
-- reaches which postcode prefix, at what bandwidth. An empty prefix matches
-- everywhere (the mobile fallback). service_qualification keeps each answered
-- check: a qualification is a fact worth reading back.

CREATE TABLE coverage_map (
    id              VARCHAR(36)  NOT NULL,
    href            VARCHAR(255),
    technology      VARCHAR(64)  NOT NULL,
    postcode_prefix VARCHAR(16)  NOT NULL,
    max_down_mbps   INTEGER      NOT NULL,
    max_up_mbps     INTEGER,
    note            VARCHAR(255),
    last_update     TIMESTAMP WITH TIME ZONE,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    CONSTRAINT pk_coverage_map PRIMARY KEY (id)
);
CREATE INDEX idx_coverage_map_tenant ON coverage_map (tenant_id);
CREATE INDEX idx_coverage_map_technology ON coverage_map (technology);

CREATE TABLE service_qualification (
    id                   VARCHAR(36)  NOT NULL,
    href                 VARCHAR(255),
    place                VARCHAR(2000),
    result               VARCHAR(10000),
    state                VARCHAR(32)  NOT NULL,
    qualification_result VARCHAR(16),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    tenant_id            VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    CONSTRAINT pk_service_qualification PRIMARY KEY (id)
);
CREATE INDEX idx_service_qualification_tenant ON service_qualification (tenant_id);
