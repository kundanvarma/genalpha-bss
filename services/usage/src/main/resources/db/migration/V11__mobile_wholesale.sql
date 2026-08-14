-- Mobile wholesale (MVNE): the MVNO owes its host MNO for the traffic its
-- subscribers burn. A per-tenant wholesale rate card (per usage type), a ledger
-- of what was rated at wholesale per period, and the IMSI range the host lends.
-- Usage-metered, the mobile sibling of the fibre per-line settlement.
CREATE TABLE wholesale_rate_card (
    id              VARCHAR(36)   NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    usage_spec_name VARCHAR(64)   NOT NULL,
    wholesale_rate  DECIMAL(18,6) NOT NULL,
    unit            VARCHAR(32),
    currency        VARCHAR(8)    NOT NULL DEFAULT 'EUR',
    host_party_id   VARCHAR(64),
    host_name       VARCHAR(255),
    last_update     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_wholesale_rate_card PRIMARY KEY (id)
);
CREATE INDEX idx_wrc_tenant ON wholesale_rate_card (tenant_id);

CREATE TABLE wholesale_usage_ledger (
    id              VARCHAR(36)   NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL DEFAULT 'genalpha',
    period_start    DATE          NOT NULL,
    usage_spec_name VARCHAR(64)   NOT NULL,
    total_units     DECIMAL(18,4) NOT NULL,
    unit            VARCHAR(32),
    wholesale_rate  DECIMAL(18,6) NOT NULL,
    amount          DECIMAL(18,2) NOT NULL,
    currency        VARCHAR(8)    NOT NULL DEFAULT 'EUR',
    host_party_id   VARCHAR(64),
    status          VARCHAR(32)   NOT NULL DEFAULT 'rated',
    created_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_wholesale_usage_ledger PRIMARY KEY (id),
    CONSTRAINT uq_wul_period_spec UNIQUE (tenant_id, period_start, usage_spec_name)
);
CREATE INDEX idx_wul_tenant_period ON wholesale_usage_ledger (tenant_id, period_start);

CREATE TABLE imsi_range (
    id            VARCHAR(36)  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    host_party_id VARCHAR(64),
    host_name     VARCHAR(255),
    prefix        VARCHAR(32),
    from_imsi     VARCHAR(32)  NOT NULL,
    to_imsi       VARCHAR(32)  NOT NULL,
    capacity      INTEGER,
    note          VARCHAR(255),
    allocated_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_imsi_range PRIMARY KEY (id)
);
CREATE INDEX idx_imsi_tenant ON imsi_range (tenant_id);
