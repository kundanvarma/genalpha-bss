-- CONFIRM-AFTER-CALL: a consumer telesales agreement is NOT BINDING
-- until the customer confirms in writing after the call
-- (angrerettloven). So the call produces an OFFER, never an order:
-- the order is only born when the confirmation token comes back —
-- and commission starts there too, because that is when the
-- agreement starts existing.
CREATE TABLE telesales_offer (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    dealer_org_id  VARCHAR(64) NOT NULL,
    store          VARCHAR(120),
    customer_id    VARCHAR(64) NOT NULL,
    customer_phone VARCHAR(32),
    offering_id    VARCHAR(64) NOT NULL,
    offering_name  VARCHAR(255),
    confirm_token  VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    product_order_id VARCHAR(36),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at   TIMESTAMP WITH TIME ZONE,
    last_update    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX idx_telesales_token ON telesales_offer (tenant_id, confirm_token);
CREATE INDEX idx_telesales_dealer ON telesales_offer (tenant_id, dealer_org_id);
CREATE INDEX idx_telesales_due ON telesales_offer (tenant_id, status, expires_at);
