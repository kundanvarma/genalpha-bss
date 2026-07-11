-- TMF685: pools of assignable resources (MSISDNs, SIM ranges). Activation
-- draws the next value; assignments record who holds what.
-- (V2 is the postgres-only RLS migration.)
CREATE TABLE resource_pool (
    id            VARCHAR(36) PRIMARY KEY,
    href          VARCHAR(255),
    tenant_id     VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name          VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    prefix        VARCHAR(32) NOT NULL,
    next_value    BIGINT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_resource_pool_tenant ON resource_pool (tenant_id);

CREATE TABLE resource_assignment (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    pool_id        VARCHAR(36) NOT NULL,
    assigned_value VARCHAR(64) NOT NULL,
    service_id     VARCHAR(36) NOT NULL,
    owner_party_id VARCHAR(64),
    assigned_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_resource_assignment_tenant ON resource_assignment (tenant_id);
CREATE INDEX idx_resource_assignment_service ON resource_assignment (service_id);
