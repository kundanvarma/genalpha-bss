-- The provider side of open access: access orders RETAILERS place with us (the
-- fibre owner) over our MEF Sonata face. We provision + notify their callback.
CREATE TABLE provider_access_order (
    id                VARCHAR(36) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    buyer_ref         VARCHAR(64),
    retailer_party_id VARCHAR(64),
    callback_url      VARCHAR(512),
    access_layer      VARCHAR(32),
    bandwidth_mbps    INTEGER,
    post_code         VARCHAR(16),
    state             VARCHAR(32) NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    activate_at       TIMESTAMP WITH TIME ZONE,
    activated_at      TIMESTAMP WITH TIME ZONE,
    last_update       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_pao_tenant ON provider_access_order (tenant_id);
CREATE INDEX idx_pao_state ON provider_access_order (tenant_id, state);
