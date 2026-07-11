CREATE TABLE ai_audit (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    use_case    VARCHAR(64)  NOT NULL,
    provider    VARCHAR(32)  NOT NULL,
    model       VARCHAR(128),
    prompt      VARCHAR(4000) NOT NULL,
    response    VARCHAR(4000) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ai_audit_tenant ON ai_audit (tenant_id, created_at);
