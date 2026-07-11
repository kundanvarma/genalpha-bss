CREATE TABLE agreement (
    id                VARCHAR(36) PRIMARY KEY,
    href              VARCHAR(255),
    tenant_id         VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name              VARCHAR(255) NOT NULL,
    agreement_type    VARCHAR(64),
    status            VARCHAR(32) NOT NULL,
    owner_party_id    VARCHAR(64),
    period_start      TIMESTAMP WITH TIME ZONE,
    period_end        TIMESTAMP WITH TIME ZONE,
    commitment_months INT,
    -- TMF refs echoed verbatim
    engaged_party     VARCHAR(2000),
    agreement_item    VARCHAR(4000),
    characteristic    VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_agreement_tenant ON agreement (tenant_id);
CREATE INDEX idx_agreement_owner ON agreement (owner_party_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
