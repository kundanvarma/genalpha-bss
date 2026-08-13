-- TMF633 Service Catalog: the ServiceSpecification the retail catalog never had.
-- A CFS (customer-facing) realised over an RFS (resource-facing) via a
-- serviceSpecRelationship; a ProductSpecification is realised by a CFS. This is
-- the SID commercial/technical split the wholesale audit flagged as missing.
CREATE TABLE service_specification (
    id                          VARCHAR(36)  NOT NULL,
    tenant_id                   VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    href                        VARCHAR(255),
    name                        VARCHAR(255) NOT NULL,
    description                 VARCHAR(1024),
    version                     VARCHAR(64),
    lifecycle_status            VARCHAR(64),
    is_bundle                   BOOLEAN,
    service_type                VARCHAR(16),
    service_spec_characteristic VARCHAR(4000),
    service_spec_relationship   VARCHAR(4000),
    last_update                 TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_service_specification PRIMARY KEY (id)
);

CREATE INDEX idx_service_specification_tenant ON service_specification (tenant_id);
CREATE INDEX idx_service_specification_type ON service_specification (service_type);

-- A ProductSpecification is "realised by" a ServiceSpecification (the CFS) — the
-- link from the sellable product down into the service catalog. TMF620/SID.
ALTER TABLE product_specification ADD COLUMN service_specification VARCHAR(2000);
