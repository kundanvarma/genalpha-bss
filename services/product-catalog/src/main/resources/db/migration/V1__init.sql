-- TMF620 Product Catalog Management — initial schema.
-- Column types must stay in step with the JPA entities: the services run with
-- hibernate.ddl-auto=validate, so any drift fails startup rather than silently
-- altering tables.

CREATE TABLE product_offering (
    id               VARCHAR(36)   NOT NULL,
    href             VARCHAR(255),
    name             VARCHAR(255)  NOT NULL,
    description      VARCHAR(2000),
    lifecycle_status VARCHAR(255),
    version          VARCHAR(255),
    CONSTRAINT pk_product_offering PRIMARY KEY (id)
);

CREATE TABLE category (
    id          VARCHAR(36)   NOT NULL,
    href        VARCHAR(255),
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(2000),
    CONSTRAINT pk_category PRIMARY KEY (id)
);

CREATE TABLE product_specification (
    id    VARCHAR(36)  NOT NULL,
    href  VARCHAR(255),
    name  VARCHAR(255) NOT NULL,
    brand VARCHAR(255),
    CONSTRAINT pk_product_specification PRIMARY KEY (id)
);
