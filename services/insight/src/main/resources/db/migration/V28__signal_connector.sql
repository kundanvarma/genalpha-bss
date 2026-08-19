-- Signal connectors (SI-P2): per-tenant bindings of external signal sources —
-- support desks, review platforms, call-transcript feeds. Same doctrine as
-- carriers: bind a NAMED adapter (kind), or wire a foreign push through the
-- generic webhook (kind http-webhook, JSON pointers map the shape). secret_ref
-- and webhook_secret_ref are env-var NAMES, never values. Every item a
-- connector delivers passes the SI-P1 PII firewall — there is no side door.
CREATE TABLE signal_connector (
    id                 VARCHAR(36)  NOT NULL,
    tenant_id          VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    name               VARCHAR(128) NOT NULL,
    kind               VARCHAR(32)  NOT NULL,
    source             VARCHAR(32)  NOT NULL,
    mode               VARCHAR(16)  NOT NULL,
    base_url           VARCHAR(255),
    secret_ref         VARCHAR(128),
    webhook_secret_ref VARCHAR(128),
    config             VARCHAR(2000),
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync_at       TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_signal_connector PRIMARY KEY (id),
    CONSTRAINT uq_signal_connector UNIQUE (tenant_id, name)
);
CREATE INDEX idx_signal_connector_tenant ON signal_connector (tenant_id);
