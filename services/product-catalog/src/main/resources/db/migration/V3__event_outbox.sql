-- Transactional outbox: an event row is written in the same database
-- transaction as the business change it describes, so a rolled-back change
-- can never produce an event and a committed change can never lose one. A
-- relay drains this table to Kafka and deletes rows only after the broker
-- acknowledges (at-least-once delivery; consumers deduplicate by eventId).

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
