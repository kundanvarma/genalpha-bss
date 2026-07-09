-- TMF637 Product Inventory Management — initial schema.
-- Column types must stay in step with the JPA entities: the services run with
-- hibernate.ddl-auto=validate, so any drift fails startup rather than silently
-- altering tables.

CREATE TABLE product (
    id                  VARCHAR(36)  NOT NULL,
    href                VARCHAR(255),
    name                VARCHAR(255) NOT NULL,
    status              VARCHAR(255),
    product_offering_id VARCHAR(255),
    billing_account_id  VARCHAR(255),
    CONSTRAINT pk_product PRIMARY KEY (id)
);
