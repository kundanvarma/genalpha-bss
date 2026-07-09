-- TMF622 Product Ordering Management — initial schema.
-- Column types must stay in step with the JPA entities: the services run with
-- hibernate.ddl-auto=validate, so any drift fails startup rather than silently
-- altering tables.

CREATE TABLE product_order (
    id                  VARCHAR(36)   NOT NULL,
    href                VARCHAR(255),
    state               VARCHAR(255)  NOT NULL,
    description         VARCHAR(2000),
    category            VARCHAR(255),
    product_offering_id VARCHAR(255),
    order_date          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_product_order PRIMARY KEY (id)
);
