-- TMF621 Trouble Ticket: a customer's problem, worked by an organisation's
-- agents. Customers see their own tickets (party scope); agents see their
-- organisation's (org scope); back-office sees all.

CREATE TABLE trouble_ticket (
    id                 VARCHAR(36)  NOT NULL,
    href               VARCHAR(255),
    name               VARCHAR(255) NOT NULL,
    description        VARCHAR(4000),
    severity           VARCHAR(32),
    status             VARCHAR(32)  NOT NULL,
    owner_party_id     VARCHAR(36),
    org_id             VARCHAR(64)  NOT NULL,
    related_entity     VARCHAR(2000),
    note               VARCHAR(8000),
    creation_date      TIMESTAMP WITH TIME ZONE,
    status_change_date TIMESTAMP WITH TIME ZONE,
    last_update        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_trouble_ticket PRIMARY KEY (id)
);
CREATE INDEX idx_ticket_owner ON trouble_ticket (owner_party_id);
CREATE INDEX idx_ticket_org ON trouble_ticket (org_id);
CREATE INDEX idx_ticket_status ON trouble_ticket (status);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
