-- VoC early-warning ledger (SI-P4): one row per (aspect, ISO week) deviation
-- so the alert fires ONCE per week per aspect however often the sweep runs.
-- The numbers that tripped it ride the row — an alert you can audit.
CREATE TABLE voc_alert (
    id             VARCHAR(36) NOT NULL,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    aspect         VARCHAR(32) NOT NULL,
    iso_week       VARCHAR(10) NOT NULL,
    week_negatives INTEGER NOT NULL,
    baseline_avg   NUMERIC(10,2) NOT NULL,
    ratio          NUMERIC(10,2) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_voc_alert PRIMARY KEY (id),
    CONSTRAINT uq_voc_alert UNIQUE (tenant_id, aspect, iso_week)
);
CREATE INDEX idx_voc_alert_tenant ON voc_alert (tenant_id, created_at);
