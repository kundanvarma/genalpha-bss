CREATE TABLE quote (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    href           VARCHAR(255),
    description    VARCHAR(500) NOT NULL,
    state          VARCHAR(32)  NOT NULL,
    intent_id      VARCHAR(36),
    owner_party_id VARCHAR(64),
    items          VARCHAR(6000) NOT NULL,
    monthly_total  NUMERIC(12,2) NOT NULL,
    currency       VARCHAR(8)   NOT NULL,
    narrative      VARCHAR(3000),
    product_order_id VARCHAR(36),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_quote_tenant ON quote (tenant_id, created_at);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
