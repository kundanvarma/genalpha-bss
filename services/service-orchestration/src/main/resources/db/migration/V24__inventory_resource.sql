-- TMF639: a stored inventory record for externally-provisioned resources
-- (a router, an antenna — things the pools never issued). The generator
-- pools stay honest counters; THIS table is where a posted resource lives.
CREATE TABLE inventory_resource (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name            VARCHAR(255) NOT NULL,
    category        VARCHAR(128),
    resource_status VARCHAR(32) NOT NULL DEFAULT 'available',
    document_json   VARCHAR(4000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_inventory_resource_name ON inventory_resource (tenant_id, name);
