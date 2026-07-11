CREATE TABLE service_order (
    id               VARCHAR(36) PRIMARY KEY,
    href             VARCHAR(255),
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    state            VARCHAR(32) NOT NULL,
    product_order_id VARCHAR(36) NOT NULL,
    owner_party_id   VARCHAR(64),
    item_name        VARCHAR(255) NOT NULL,
    offering_id      VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_order_tenant ON service_order (tenant_id);
CREATE INDEX idx_service_order_product_order ON service_order (product_order_id);

CREATE TABLE service (
    id               VARCHAR(36) PRIMARY KEY,
    href             VARCHAR(255),
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name             VARCHAR(255) NOT NULL,
    state            VARCHAR(32) NOT NULL,
    service_order_id VARCHAR(36) NOT NULL,
    owner_party_id   VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_tenant ON service (tenant_id);
CREATE INDEX idx_service_owner ON service (owner_party_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
