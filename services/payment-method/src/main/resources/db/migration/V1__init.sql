CREATE TABLE payment_method (
    id             VARCHAR(36) PRIMARY KEY,
    href           VARCHAR(255),
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    owner_party_id VARCHAR(64) NOT NULL,
    method_type    VARCHAR(32) NOT NULL,
    -- presentation data only; the PAN never reaches this system
    brand          VARCHAR(32),
    last_four      VARCHAR(4),
    expiry         VARCHAR(7),
    -- opaque PSP vault reference (dev: mock token)
    psp_token      VARCHAR(64) NOT NULL,
    preferred      BOOLEAN NOT NULL DEFAULT FALSE,
    status         VARCHAR(16) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_payment_method_tenant ON payment_method (tenant_id);
CREATE INDEX idx_payment_method_owner ON payment_method (owner_party_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
