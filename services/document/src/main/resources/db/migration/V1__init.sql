-- TMF667: managed content. The binary lives in-row (dev-simple; a cloud
-- deployment would point storage at S3/blob behind the same API).
CREATE TABLE document (
    id           VARCHAR(36) PRIMARY KEY,
    href         VARCHAR(255),
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name         VARCHAR(255) NOT NULL,
    category     VARCHAR(64),
    content_type VARCHAR(64) NOT NULL,
    content      BYTEA NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_document_tenant ON document (tenant_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
