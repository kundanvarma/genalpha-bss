-- Async activation: a large audience export to an ad/social platform runs as a
-- background JOB so the request never blocks. The caller gets a job id and polls.
CREATE TABLE activation_job (
    id                  VARCHAR(36) NOT NULL,
    tenant_id           VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    audience_id         VARCHAR(36) NOT NULL,
    external_audience_id VARCHAR(128),
    mode                VARCHAR(16),
    status              VARCHAR(16) NOT NULL DEFAULT 'queued',
    members             INTEGER,
    pushed              INTEGER,
    skipped             INTEGER,
    error               VARCHAR(500),
    created_at          TIMESTAMP WITH TIME ZONE,
    finished_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_activation_job PRIMARY KEY (id)
);
CREATE INDEX idx_activation_job_tenant ON activation_job (tenant_id);
