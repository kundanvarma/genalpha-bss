-- TMF632 Party Management + TMF666 Account Management — initial schema.
-- Column types must stay in step with the JPA entities: the services run with
-- hibernate.ddl-auto=validate, so any drift fails startup rather than silently
-- altering tables.

CREATE TABLE individual (
    id          VARCHAR(36)  NOT NULL,
    href        VARCHAR(255),
    given_name  VARCHAR(255),
    family_name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_individual PRIMARY KEY (id)
);

CREATE TABLE organization (
    id           VARCHAR(36)  NOT NULL,
    href         VARCHAR(255),
    name         VARCHAR(255) NOT NULL,
    trading_name VARCHAR(255),
    CONSTRAINT pk_organization PRIMARY KEY (id)
);

CREATE TABLE billing_account (
    id               VARCHAR(36)  NOT NULL,
    href             VARCHAR(255),
    name             VARCHAR(255) NOT NULL,
    state            VARCHAR(255),
    related_party_id VARCHAR(255),
    CONSTRAINT pk_billing_account PRIMARY KEY (id)
);
