-- TMF640 Service Activation and Configuration: a FACE over the same service
-- inventory, not a second one. A service declared through POST /service
-- lands in the `service` table like every other line; this thin extension
-- keeps what the activation side adds — the caller's own document (spec
-- ref, characteristics, places, parties…) and the activation date — plus
-- the monitor rows that report the state of each activation request.
CREATE TABLE service_activation (
    service_id     VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    service_date   TIMESTAMP WITH TIME ZONE NOT NULL,
    document_json  VARCHAR(16000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_activation_tenant ON service_activation (tenant_id);

CREATE TABLE service_monitor (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    service_id     VARCHAR(36) NOT NULL,
    state          VARCHAR(32) NOT NULL,
    source_href    VARCHAR(255),
    request_json   VARCHAR(16000),
    response_json  VARCHAR(16000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_service_monitor_tenant ON service_monitor (tenant_id, service_id);
