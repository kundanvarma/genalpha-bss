-- Mobile wholesale PROVIDER face (W-M7): the host MNO / MVNE billing external
-- MVNOs (who run their own BSS) for the traffic they carry. Per-MVNO rate cards
-- are the SLA/tier lever — a premium MVNO and a budget MVNO owe different rates.
-- The mirror of the fibre provider side, usage-metered.
CREATE TABLE provider_rate_card (
    id              VARCHAR(36)   NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    mvno_party_id   VARCHAR(64),   -- NULL = the default rate for any MVNO
    mvno_name       VARCHAR(255),
    usage_spec_name VARCHAR(64)   NOT NULL,
    rate            DECIMAL(18,6) NOT NULL,
    unit            VARCHAR(32),
    currency        VARCHAR(8)    NOT NULL DEFAULT 'EUR',
    last_update     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_provider_rate_card PRIMARY KEY (id)
);
CREATE INDEX idx_prc_tenant ON provider_rate_card (tenant_id);

CREATE TABLE provider_usage_ledger (
    id              VARCHAR(36)   NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    mvno_party_id   VARCHAR(64)   NOT NULL,
    mvno_name       VARCHAR(255),
    period_start    DATE          NOT NULL,
    usage_spec_name VARCHAR(64)   NOT NULL,
    total_units     DECIMAL(18,4) NOT NULL,
    unit            VARCHAR(32),
    rate            DECIMAL(18,6) NOT NULL,
    amount          DECIMAL(18,2) NOT NULL,
    currency        VARCHAR(8)    NOT NULL DEFAULT 'EUR',
    status          VARCHAR(32)   NOT NULL DEFAULT 'rated',
    created_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_provider_usage_ledger PRIMARY KEY (id),
    CONSTRAINT uq_pul_mvno_period_spec UNIQUE (tenant_id, mvno_party_id, period_start, usage_spec_name)
);
CREATE INDEX idx_pul_tenant_period ON provider_usage_ledger (tenant_id, period_start);
