-- TMF663 Shopping Cart: pre-order commercial state, owned by core commerce so
-- every channel sees the same cart. A guest cart's id is its bearer secret
-- until a sign-in claims it for a party; checked-out carts are immutable
-- history; abandoned ones are the martech trigger.

CREATE TABLE shopping_cart (
    id              VARCHAR(36)  NOT NULL,
    href            VARCHAR(255),
    status          VARCHAR(32)  NOT NULL,
    owner_party_id  VARCHAR(36),
    cart_item       VARCHAR(8000),
    related_entity  VARCHAR(2000),
    created_at      TIMESTAMP WITH TIME ZONE,
    last_update     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_shopping_cart PRIMARY KEY (id)
);
CREATE INDEX idx_cart_owner ON shopping_cart (owner_party_id);
CREATE INDEX idx_cart_status_update ON shopping_cart (status, last_update);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
