-- TMF632 + TMF666 CTK conformance.
--
-- Organization: the spec has no mandatory name; the CTK creates organizations
-- carrying only tradingName. BillingAccount: relatedParty is mandatory in
-- responses; stored as a JSON document. The remaining tables are the TMF666
-- account resources the CTK exercises.

ALTER TABLE organization ALTER COLUMN name DROP NOT NULL;
ALTER TABLE billing_account ADD COLUMN related_party VARCHAR(4000);

CREATE TABLE bill_format (
    id   VARCHAR(36)  NOT NULL,
    href VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_bill_format PRIMARY KEY (id)
);

CREATE TABLE billing_cycle_specification (
    id   VARCHAR(36)  NOT NULL,
    href VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_billing_cycle_specification PRIMARY KEY (id)
);

CREATE TABLE financial_account (
    id   VARCHAR(36)  NOT NULL,
    href VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_financial_account PRIMARY KEY (id)
);

CREATE TABLE party_account (
    id            VARCHAR(36)  NOT NULL,
    href          VARCHAR(255),
    name          VARCHAR(255) NOT NULL,
    related_party VARCHAR(4000),
    CONSTRAINT pk_party_account PRIMARY KEY (id)
);

CREATE TABLE settlement_account (
    id            VARCHAR(36)  NOT NULL,
    href          VARCHAR(255),
    name          VARCHAR(255) NOT NULL,
    related_party VARCHAR(4000),
    CONSTRAINT pk_settlement_account PRIMARY KEY (id)
);

CREATE TABLE bill_presentation_media (
    id   VARCHAR(36)  NOT NULL,
    href VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_bill_presentation_media PRIMARY KEY (id)
);
