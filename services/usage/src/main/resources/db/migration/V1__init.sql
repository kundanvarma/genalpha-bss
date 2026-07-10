-- TMF635/677 Usage: the BSS side of charging. Records arrive through the
-- ingest seam (mediation/OCS in production, a simulator in dev), rating
-- aggregates a period against per-offering allowances into overage charges,
-- and the consumption view tells the customer how much bucket is left.
-- Real-time credit control stays in the network's OCS/CHF — never here.

CREATE TABLE usage_specification (
    id          VARCHAR(36)  NOT NULL,
    href        VARCHAR(255),
    name        VARCHAR(255) NOT NULL,
    units       VARCHAR(32)  NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_usage_specification PRIMARY KEY (id)
);

CREATE TABLE usage_allowance (
    id                  VARCHAR(36)  NOT NULL,
    href                VARCHAR(255),
    product_offering    VARCHAR(2000),
    product_offering_id VARCHAR(36)  NOT NULL,
    usage_spec_name     VARCHAR(255) NOT NULL,
    allowance_value     NUMERIC(12, 3) NOT NULL,
    units               VARCHAR(32)  NOT NULL,
    overage_price_value NUMERIC(12, 4) NOT NULL,
    overage_price_unit  VARCHAR(8)   NOT NULL,
    last_update         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_usage_allowance PRIMARY KEY (id)
);
CREATE INDEX idx_allowance_offering ON usage_allowance (product_offering_id);

CREATE TABLE usage_record (
    id                  VARCHAR(36)  NOT NULL,
    href                VARCHAR(255),
    usage_spec_name     VARCHAR(255) NOT NULL,
    usage_date          TIMESTAMP WITH TIME ZONE NOT NULL,
    usage_value         NUMERIC(12, 3) NOT NULL,
    units               VARCHAR(32)  NOT NULL,
    owner_party_id      VARCHAR(36)  NOT NULL,
    product_offering_id VARCHAR(36),
    status              VARCHAR(32)  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_usage_record PRIMARY KEY (id)
);
CREATE INDEX idx_usage_owner ON usage_record (owner_party_id, status);
CREATE INDEX idx_usage_date ON usage_record (usage_date);

CREATE TABLE rated_charge (
    id              VARCHAR(36)  NOT NULL,
    owner_party_id  VARCHAR(36)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    amount_value    NUMERIC(12, 2) NOT NULL,
    amount_unit     VARCHAR(8)   NOT NULL,
    period_start    DATE         NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_rated_charge PRIMARY KEY (id)
);
CREATE INDEX idx_rated_owner_period ON rated_charge (owner_party_id, period_start);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
