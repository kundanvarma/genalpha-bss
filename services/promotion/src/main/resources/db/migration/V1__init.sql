CREATE TABLE promotion (
    id               VARCHAR(36) PRIMARY KEY,
    href             VARCHAR(255),
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name             VARCHAR(255) NOT NULL,
    description      VARCHAR(2000),
    code             VARCHAR(64) NOT NULL,
    lifecycle_status VARCHAR(32) NOT NULL,
    percentage       NUMERIC(5, 2) NOT NULL,
    duration_months  INT,
    -- JSON list of offering ids the promotion applies to; empty/null = all
    applies_to       VARCHAR(2000),
    valid_from       TIMESTAMP WITH TIME ZONE,
    valid_until      TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_promotion_tenant ON promotion (tenant_id);
CREATE UNIQUE INDEX ux_promotion_code ON promotion (tenant_id, code);

CREATE TABLE promotion_redemption (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    promotion_id   VARCHAR(36) NOT NULL,
    promotion_name VARCHAR(255) NOT NULL,
    code           VARCHAR(64) NOT NULL,
    owner_party_id VARCHAR(64) NOT NULL,
    percentage     NUMERIC(5, 2) NOT NULL,
    -- JSON list of offering ids the redemption discounts; empty/null = all
    applies_to     VARCHAR(2000),
    months_left    INT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_redemption_tenant ON promotion_redemption (tenant_id);
CREATE INDEX idx_redemption_owner ON promotion_redemption (owner_party_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
