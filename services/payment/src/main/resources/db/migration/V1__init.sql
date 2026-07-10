-- TMF676 Payment: the money side of an order's one-time charges. A payment is
-- authorized at checkout (mock PSP in dev), captured when the order completes,
-- voided when it cancels. Card numbers never touch this database — last4 only.

CREATE TABLE payment (
    id                  VARCHAR(36)  NOT NULL,
    href                VARCHAR(255),
    description         VARCHAR(2000),
    status              VARCHAR(32)  NOT NULL,
    amount_value        NUMERIC(12, 2) NOT NULL,
    amount_unit         VARCHAR(8)   NOT NULL,
    method_type         VARCHAR(64),
    method_label        VARCHAR(64),
    authorization_code  VARCHAR(64),
    correlator_id       VARCHAR(36),
    owner_party_id      VARCHAR(36),
    payment_date        TIMESTAMP WITH TIME ZONE,
    last_update         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_payment PRIMARY KEY (id)
);
CREATE INDEX idx_payment_owner ON payment (owner_party_id);
CREATE INDEX idx_payment_correlator ON payment (correlator_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
