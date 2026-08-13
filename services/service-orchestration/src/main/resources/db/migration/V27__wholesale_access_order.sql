-- Open access: the access-seeker order we place UPSTREAM to a third-party fibre
-- owner to activate wholesale access for a retail line. The wholesale mirror of a
-- service order — its own lifecycle, the owner + access layer, and the owner OSS's
-- own reference (MEF LSO Sonata in production; a mock in dev).
CREATE TABLE wholesale_access_order (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    product_order_id VARCHAR(36),
    service_id       VARCHAR(36),
    owner_party_id   VARCHAR(64),
    access_owner     VARCHAR(64) NOT NULL,
    access_layer     VARCHAR(32),
    bandwidth_mbps   INTEGER,
    post_code        VARCHAR(16),
    state            VARCHAR(32) NOT NULL,
    external_id      VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at     TIMESTAMP WITH TIME ZONE,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_wao_tenant ON wholesale_access_order (tenant_id);
CREATE INDEX idx_wao_order ON wholesale_access_order (product_order_id);
CREATE INDEX idx_wao_state ON wholesale_access_order (tenant_id, state);
