-- Number portability: a port-in brings the customer's existing number from
-- another operator; a port-out lets them leave with it. The inter-operator
-- coordination runs through a country clearinghouse (NRDB in Norway) behind
-- a pluggable gateway — this table is the BSS's view of the request.
CREATE TABLE porting_order (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    href              VARCHAR(255),
    direction         VARCHAR(16)  NOT NULL,   -- portIn | portOut
    phone_number      VARCHAR(32)  NOT NULL,
    country           VARCHAR(2)   NOT NULL,   -- ISO 3166-1 alpha-2
    other_operator    VARCHAR(128),            -- donor (portIn) / recipient (portOut)
    owner_party_id    VARCHAR(64),
    product_order_id  VARCHAR(36),
    gateway           VARCHAR(32)  NOT NULL,   -- which clearinghouse validated it
    status            VARCHAR(24)  NOT NULL,
    reject_reason     VARCHAR(255),
    requested_cutover TIMESTAMP WITH TIME ZONE,
    scheduled_cutover TIMESTAMP WITH TIME ZONE,
    completed_at      TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_porting_tenant ON porting_order (tenant_id, created_at);
CREATE INDEX idx_porting_party ON porting_order (tenant_id, owner_party_id, status);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
