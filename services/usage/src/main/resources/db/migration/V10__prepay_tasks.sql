-- TMF654 task resources on the prepay facade: created buckets and the
-- topup/adjust/reserveBalance task log, persisted in the TMF shape so the
-- API face is conformance-grade. The OCS stays the charging master — a
-- task row RECORDS the request; where a real credit path exists (top-up)
-- the OCS did the arithmetic before the row was written.
CREATE TABLE prepay_task (
    id            VARCHAR(36) PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    resource_type VARCHAR(32) NOT NULL,
    status        VARCHAR(32),
    usage_type    VARCHAR(64),
    payload_json  VARCHAR(4000),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_prepay_task_tenant_type ON prepay_task (tenant_id, resource_type);
