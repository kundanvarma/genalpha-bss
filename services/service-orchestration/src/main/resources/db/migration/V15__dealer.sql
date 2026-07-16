-- THE DEALER CHANNEL: retail chains sell our activations (the
-- CSP/Elkjop/Power model). Three tables carry it:
--  * dealer_agreement — being a dealer IS a row: the org, the commission
--    per activation. No row, no dealer powers.
--  * starter_kit — the SIM in the box: activation code + pre-minted
--    ICCID/PUK + the dealer attribution BAKED INTO THE KIT, so a kit
--    sold like a chocolate bar still credits the store that sold it.
--  * commission_entry — money out with money-in discipline: PENDING on
--    activation, EARNED after the withdrawal window (angrerett), or
--    CLAWED_BACK with the reason when the customer leaves inside it.
CREATE TABLE dealer_agreement (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    dealer_org_id    VARCHAR(64) NOT NULL,
    name             VARCHAR(120) NOT NULL,
    commission_value NUMERIC(12,2) NOT NULL,
    commission_unit  VARCHAR(8) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX idx_dealer_agreement_org ON dealer_agreement (tenant_id, dealer_org_id);

CREATE TABLE starter_kit (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    activation_code  VARCHAR(16) NOT NULL,
    iccid            VARCHAR(22) NOT NULL,
    puk_ciphertext   VARCHAR(255) NOT NULL,
    dealer_org_id    VARCHAR(64) NOT NULL,
    store            VARCHAR(120),
    status           VARCHAR(16) NOT NULL,
    product_order_id VARCHAR(36),
    activated_by     VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at     TIMESTAMP WITH TIME ZONE,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX idx_kit_code ON starter_kit (tenant_id, activation_code);
CREATE INDEX idx_kit_dealer ON starter_kit (tenant_id, dealer_org_id);
CREATE INDEX idx_kit_order ON starter_kit (tenant_id, product_order_id);

CREATE TABLE commission_entry (
    id                VARCHAR(36) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    dealer_org_id     VARCHAR(64) NOT NULL,
    store             VARCHAR(120),
    product_order_id  VARCHAR(36),
    service_id        VARCHAR(36),
    customer_party_id VARCHAR(64),
    offering_name     VARCHAR(255),
    amount_value      NUMERIC(12,2) NOT NULL,
    amount_unit       VARCHAR(8) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    reason            VARCHAR(255),
    accrued_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    hardens_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_commission_dealer ON commission_entry (tenant_id, dealer_org_id);
CREATE INDEX idx_commission_service ON commission_entry (tenant_id, service_id);
CREATE INDEX idx_commission_due ON commission_entry (tenant_id, status, hardens_at);
