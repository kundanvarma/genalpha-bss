-- The scorer's memory: one alert per (tenant, customer, reason) so the same
-- risk is never re-broadcast; and the transactional outbox for its events.
CREATE TABLE churn_alert (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    party_id    VARCHAR(64)  NOT NULL,
    reason      VARCHAR(64)  NOT NULL,
    score       NUMERIC(4,3) NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX ux_churn_alert_once ON churn_alert (tenant_id, party_id, reason);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
