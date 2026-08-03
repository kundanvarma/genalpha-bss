-- TMF700 + TMF697: the parcel and the visit become RESOURCES. Today's
-- physical fulfilment is a silent wait ended by a CSR button; these two
-- tables give it states, an API for the warehouse/installer, and a
-- machine completion rule — the button becomes optional.
CREATE TABLE shipping_order (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    product_order_id VARCHAR(64) NOT NULL,
    owner_party_id   VARCHAR(64),
    state            VARCHAR(24) NOT NULL,
    items_json       VARCHAR(4000),
    place_json       VARCHAR(2000),
    tracking_ref     VARCHAR(64),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_shipping_order_order ON shipping_order (tenant_id, product_order_id);
CREATE INDEX idx_shipping_order_state ON shipping_order (tenant_id, state);

CREATE TABLE work_order (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    product_order_id VARCHAR(64) NOT NULL,
    appointment_id   VARCHAR(36),
    owner_party_id   VARCHAR(64),
    state            VARCHAR(24) NOT NULL,
    place_json       VARCHAR(2000),
    note             VARCHAR(512),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_work_order_order ON work_order (tenant_id, product_order_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
