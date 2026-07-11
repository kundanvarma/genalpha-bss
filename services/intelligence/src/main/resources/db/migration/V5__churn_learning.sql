-- The learning provision: feature snapshots accumulate from the day the
-- deployment goes live; outcomes label them; the model table holds what
-- the trainer fit. Production quality is a function of months of this
-- data (or an import of the operator's history) — not of shipping day.
CREATE TABLE churn_feature_snapshot (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    party_id      VARCHAR(64)  NOT NULL,
    snapshot_date DATE         NOT NULL,
    days_to_commitment_end NUMERIC(8,1) NOT NULL,
    max_usage_ratio        NUMERIC(6,3) NOT NULL,
    tickets_last_30d       NUMERIC(5,0) NOT NULL,
    open_ticket_during_outage NUMERIC(1,0) NOT NULL,
    taken_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX ux_churn_snapshot_daily ON churn_feature_snapshot (tenant_id, party_id, snapshot_date);

CREATE TABLE churn_outcome (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    party_id    VARCHAR(64) NOT NULL,
    churned     BOOLEAN     NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX ux_churn_outcome_once ON churn_outcome (tenant_id, party_id);

CREATE TABLE churn_model (
    tenant_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    parameters   VARCHAR(4000) NOT NULL,
    sample_count INT          NOT NULL,
    positives    INT          NOT NULL,
    trained_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
