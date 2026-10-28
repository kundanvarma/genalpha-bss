-- Partner activations that are not simply done (#355): waiting on the partner,
-- refused by it, or ended here and not yet confirmed ended there. The partner
-- dedupes on our request key (the service id), so the sweep may always ASK
-- before it sends again. One row per service.
CREATE TABLE partner_activation (
    tenant_id        VARCHAR(64)  NOT NULL,
    id               VARCHAR(36)  NOT NULL,
    service_id       VARCHAR(36)  NOT NULL,
    product_order_id VARCHAR(64),
    order_item_id    VARCHAR(64),
    offering_name    VARCHAR(255) NOT NULL,
    owner_party_id   VARCHAR(64),
    state            VARCHAR(24)  NOT NULL,
    reason           VARCHAR(500),
    attempts         INTEGER      NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_partner_activation PRIMARY KEY (id),
    CONSTRAINT uq_partner_activation_service UNIQUE (tenant_id, service_id)
);
CREATE INDEX ix_partner_activation_state ON partner_activation (tenant_id, state);
