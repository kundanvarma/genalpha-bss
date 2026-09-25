-- TMF634 Resource Catalog: the ResourceSpecification, third layer of the SID
-- split (product spec -> CFS -> RFS -> resource spec). A resource spec names
-- the SEAM an adapter provides (number pool, SIM platform, online charging,
-- slice, wholesale access, partner entitlement, CPE) — never a vendor; the
-- tenant's configuration picks the vendor behind each seam. Served by the
-- product-catalog component beside TMF633 (ADR-0021). Both migration folders
-- share one version space: this is the table, V25 (postgresql) is its policy.
CREATE TABLE resource_specification (
    id                           VARCHAR(36)  NOT NULL,
    tenant_id                    VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    href                         VARCHAR(255),
    name                         VARCHAR(255) NOT NULL,
    description                  VARCHAR(1024),
    version                      VARCHAR(64),
    lifecycle_status             VARCHAR(64),
    category                     VARCHAR(64),
    is_bundle                    BOOLEAN,
    resource_spec_characteristic VARCHAR(4000),
    resource_spec_relationship   VARCHAR(4000),
    last_update                  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_resource_specification PRIMARY KEY (id)
);

CREATE INDEX idx_resource_specification_tenant ON resource_specification (tenant_id);
CREATE INDEX idx_resource_specification_category ON resource_specification (category);
