-- TMF678 Customer Bill Management: a billing run rates every customer's
-- active inventory products against the catalog's recurring prices and cuts
-- one bill per customer per period. Bills settle by capturing a TMF676
-- payment; they void nothing and delete nothing.

CREATE TABLE customer_bill (
    id              VARCHAR(36)  NOT NULL,
    href            VARCHAR(255),
    bill_no         VARCHAR(64)  NOT NULL,
    state           VARCHAR(32)  NOT NULL,
    amount_due_value NUMERIC(12, 2) NOT NULL,
    amount_due_unit VARCHAR(8)   NOT NULL,
    period_start    DATE         NOT NULL,
    period_end      DATE         NOT NULL,
    owner_party_id  VARCHAR(36)  NOT NULL,
    payment         VARCHAR(2000),
    bill_date       TIMESTAMP WITH TIME ZONE,
    last_update     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_customer_bill PRIMARY KEY (id)
);
CREATE INDEX idx_customer_bill_owner ON customer_bill (owner_party_id);
CREATE UNIQUE INDEX ux_customer_bill_owner_period ON customer_bill (owner_party_id, period_start);

CREATE TABLE applied_billing_rate (
    id              VARCHAR(36)  NOT NULL,
    bill_id         VARCHAR(36)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    rate_type       VARCHAR(64)  NOT NULL,
    amount_value    NUMERIC(12, 2) NOT NULL,
    amount_unit     VARCHAR(8)   NOT NULL,
    product         VARCHAR(2000),
    owner_party_id  VARCHAR(36)  NOT NULL,
    rate_date       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_applied_billing_rate PRIMARY KEY (id),
    CONSTRAINT fk_rate_bill FOREIGN KEY (bill_id) REFERENCES customer_bill (id)
);
CREATE INDEX idx_applied_rate_bill ON applied_billing_rate (bill_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
