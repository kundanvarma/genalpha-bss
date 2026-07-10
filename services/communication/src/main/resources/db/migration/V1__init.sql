-- TMF681 Communication: the notifications a customer actually sees. Messages
-- are minted from the domain event stream (order placed, bill ready, ticket
-- resolved, installer booked) — idempotent on the source event id, since the
-- outbox delivers at-least-once.

CREATE TABLE communication_message (
    id                VARCHAR(36)   NOT NULL,
    href              VARCHAR(255),
    subject           VARCHAR(255)  NOT NULL,
    content           VARCHAR(4000),
    message_type      VARCHAR(32)   NOT NULL,
    status            VARCHAR(32)   NOT NULL,
    receiver_party_id VARCHAR(36)   NOT NULL,
    source_event_id   VARCHAR(36),
    source_event_type VARCHAR(128),
    created_at        TIMESTAMP WITH TIME ZONE,
    last_update       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_communication_message PRIMARY KEY (id)
);
CREATE INDEX idx_message_receiver ON communication_message (receiver_party_id);
CREATE UNIQUE INDEX ux_message_source_event ON communication_message (source_event_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
