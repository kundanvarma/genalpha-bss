-- TMF701-shaped process layer: design intent as DATA (specifications),
-- run-time flows PROJECTED from the event streams the fleet already
-- publishes, each flow's cross-system timeline journaled, and STUCK as
-- a state: a task past its owed time allowance goes failed and says so
-- on the bus. Choreography runs the flows; this layer explains them.
CREATE TABLE process_flow_spec (
    code             VARCHAR(64) NOT NULL,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512),
    tasks_json       VARCHAR(4000) NOT NULL,
    last_update      TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE process_flow (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    spec_code        VARCHAR(64) NOT NULL,
    correlation_id   VARCHAR(64) NOT NULL,
    party_id         VARCHAR(64),
    state            VARCHAR(24) NOT NULL,
    message          VARCHAR(512),
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    last_update      TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_process_flow_corr ON process_flow (tenant_id, correlation_id);
CREATE INDEX idx_process_flow_state ON process_flow (tenant_id, state);

CREATE TABLE task_flow (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    process_flow_id  VARCHAR(36) NOT NULL,
    code             VARCHAR(64) NOT NULL,
    name             VARCHAR(128),
    seq              INT NOT NULL,
    state            VARCHAR(24) NOT NULL,
    allowance_seconds BIGINT,
    message          VARCHAR(512),
    started_at       TIMESTAMP WITH TIME ZONE,
    completed_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_task_flow_process ON task_flow (tenant_id, process_flow_id);

-- The cross-system timeline, persisted per flow (the correlation recipe
-- Live Flow uses, minus the deliberate ephemerality).
CREATE TABLE process_event (
    id               VARCHAR(36) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    process_flow_id  VARCHAR(36) NOT NULL,
    event_type       VARCHAR(128) NOT NULL,
    source_topic     VARCHAR(64),
    event_time       TIMESTAMP WITH TIME ZONE,
    digest           VARCHAR(600)
);

CREATE INDEX idx_process_event_flow ON process_event (tenant_id, process_flow_id);

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
