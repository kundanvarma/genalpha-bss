-- TMF683 Party Interaction: every touchpoint with a customer — a call taken,
-- a chat handled, a shop visit. Written once, never edited: it is the
-- history a CSR reads before saying "I see you called yesterday about...".

CREATE TABLE party_interaction (
    id                VARCHAR(36)  NOT NULL,
    href              VARCHAR(255),
    description       VARCHAR(4000) NOT NULL,
    channel           VARCHAR(32),
    direction         VARCHAR(16),
    status            VARCHAR(32)  NOT NULL,
    customer_party_id VARCHAR(36)  NOT NULL,
    agent_id          VARCHAR(64),
    org_id            VARCHAR(64)  NOT NULL,
    interaction_date  TIMESTAMP WITH TIME ZONE,
    last_update       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_party_interaction PRIMARY KEY (id)
);
CREATE INDEX idx_interaction_customer ON party_interaction (customer_party_id);
CREATE INDEX idx_interaction_org ON party_interaction (org_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
