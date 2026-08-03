-- TMF688: the fleet is event-native; this hub gives OUTSIDERS the
-- standard subscription — register a callback + filter, receive the
-- same envelopes the fleet exchanges, with a delivery ledger, retries
-- with backoff, and a dead-letter state that is never silent.
CREATE TABLE hub_subscription (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    callback         VARCHAR(512) NOT NULL,
    event_types_json VARCHAR(2000),
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_hub_sub_tenant ON hub_subscription (tenant_id, active);

CREATE TABLE hub_delivery (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    subscription_id  VARCHAR(36) NOT NULL,
    event_type       VARCHAR(128),
    payload          VARCHAR(8000) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    attempts         INT NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP WITH TIME ZONE,
    last_error       VARCHAR(256),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_hub_delivery_pending ON hub_delivery (status, next_attempt_at);
CREATE INDEX idx_hub_delivery_sub ON hub_delivery (tenant_id, subscription_id);

CREATE TABLE tick_lock (
    name         VARCHAR(80) PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(120)             NOT NULL
);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
