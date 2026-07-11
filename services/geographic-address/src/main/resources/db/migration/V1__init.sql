CREATE TABLE geographic_address (
    id           VARCHAR(36) PRIMARY KEY,
    href         VARCHAR(255),
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    street1      VARCHAR(255),
    street2      VARCHAR(255),
    post_code    VARCHAR(32),
    city         VARCHAR(128),
    state_or_province VARCHAR(128),
    country      VARCHAR(64),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_geographic_address_tenant ON geographic_address (tenant_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
