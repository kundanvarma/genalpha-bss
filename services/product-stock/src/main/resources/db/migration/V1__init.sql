-- TMF687 Product Stock: what is on the shelf, and who has dibs on it.
-- available = stocked - sum(active reservations); reservations are made when
-- an order is placed, consumed when it completes, released when it cancels.

CREATE TABLE product_stock (
    id                      VARCHAR(36)  NOT NULL,
    href                    VARCHAR(255),
    name                    VARCHAR(255) NOT NULL,
    product_offering        VARCHAR(4000),
    product_offering_id     VARCHAR(36),
    stocked_amount          INTEGER      NOT NULL,
    stocked_units           VARCHAR(64),
    last_update             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_product_stock PRIMARY KEY (id)
);
CREATE INDEX idx_product_stock_offering ON product_stock (product_offering_id);

CREATE TABLE stock_reservation (
    id               VARCHAR(36)  NOT NULL,
    product_stock_id VARCHAR(36)  NOT NULL,
    order_id         VARCHAR(36)  NOT NULL,
    quantity         INTEGER      NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stock_reservation PRIMARY KEY (id),
    CONSTRAINT fk_reservation_stock FOREIGN KEY (product_stock_id) REFERENCES product_stock (id)
);
CREATE INDEX idx_stock_reservation_order ON stock_reservation (order_id);
CREATE INDEX idx_stock_reservation_stock ON stock_reservation (product_stock_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
