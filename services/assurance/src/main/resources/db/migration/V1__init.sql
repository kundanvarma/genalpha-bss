CREATE TABLE alarm (
    id             VARCHAR(36) PRIMARY KEY,
    href           VARCHAR(255),
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    alarmed_object VARCHAR(128) NOT NULL,
    alarm_type     VARCHAR(64),
    severity       VARCHAR(16) NOT NULL,
    state          VARCHAR(16) NOT NULL,
    probable_cause VARCHAR(255),
    raised_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    cleared_at     TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_alarm_tenant ON alarm (tenant_id);
CREATE INDEX idx_alarm_object ON alarm (alarmed_object);

CREATE TABLE service_problem (
    id              VARCHAR(36) PRIMARY KEY,
    href            VARCHAR(255),
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name            VARCHAR(255) NOT NULL,
    description     VARCHAR(2000),
    status          VARCHAR(16) NOT NULL,
    affected_object VARCHAR(128) NOT NULL,
    origin_alarm_id VARCHAR(36),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at     TIMESTAMP WITH TIME ZONE,
    last_update     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_problem_tenant ON service_problem (tenant_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
