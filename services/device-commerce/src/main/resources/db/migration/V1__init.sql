-- Device commerce: financing agreements (operator-book / third-party loan /
-- BNPL), trade-in from instant estimate to graded settlement, the 14-day
-- withdrawal case, the tenant-editable residual table, and the blacklist
-- flag stub. All tenant-scoped; RLS policies live in the postgres-only V2.

CREATE TABLE device_agreement (
    id                      VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id               VARCHAR(64)  NOT NULL,
    href                    VARCHAR(255),
    party_id                VARCHAR(64)  NOT NULL,
    subscription_ref        VARCHAR(64),
    order_ref               VARCHAR(64),
    device_ref              VARCHAR(128),
    imei                    VARCHAR(32),
    serial_no               VARCHAR(64),
    principal               NUMERIC(12,2) NOT NULL,
    term_months             INT          NOT NULL,
    monthly_amount          NUMERIC(12,2) NOT NULL,
    financing_model         VARCHAR(24)  NOT NULL,   -- OPERATOR_BOOK | THIRD_PARTY_LOAN | BNPL
    financier_ref           VARCHAR(128),
    external_agreement_no   VARCHAR(64),
    title_holder            VARCHAR(24),
    upgrade_rule_type       VARCHAR(16),             -- paidSharePct | month
    upgrade_rule_value      NUMERIC(12,2),
    residual_value          NUMERIC(12,2),
    total_cost_of_ownership NUMERIC(12,2) NOT NULL,  -- the ombudsman lesson: always present
    subsidy_amount          NUMERIC(12,2),
    shipping_cost           NUMERIC(12,2),
    currency                VARCHAR(8)   NOT NULL,
    payment_ref             VARCHAR(64),
    installments_paid       INT          NOT NULL DEFAULT 0,
    payout_received_at      TIMESTAMP WITH TIME ZONE,
    delivered_at            TIMESTAMP WITH TIME ZONE,
    trade_in_delta          NUMERIC(12,2),
    status                  VARCHAR(16)  NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update             TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_device_agreement_tenant ON device_agreement (tenant_id, created_at);
CREATE INDEX idx_device_agreement_party ON device_agreement (tenant_id, party_id, status);
CREATE INDEX idx_device_agreement_order ON device_agreement (tenant_id, order_ref);

CREATE TABLE trade_in_valuation (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    href            VARCHAR(255),
    party_id        VARCHAR(64),
    imei            VARCHAR(32)  NOT NULL,
    device_ref      VARCHAR(128) NOT NULL,
    condition_json  VARCHAR(2000),
    estimated_value NUMERIC(12,2) NOT NULL,
    final_value     NUMERIC(12,2),
    delta           NUMERIC(12,2),
    currency        VARCHAR(8)   NOT NULL,
    offer_expiry    TIMESTAMP WITH TIME ZONE NOT NULL,
    channel         VARCHAR(32),
    payment_ref     VARCHAR(64),
    refund_ref      VARCHAR(64),
    agreement_ref   VARCHAR(36),
    status          VARCHAR(24)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_trade_in_tenant ON trade_in_valuation (tenant_id, created_at);
CREATE INDEX idx_trade_in_party ON trade_in_valuation (tenant_id, party_id, status);

CREATE TABLE grading_event (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    valuation_ref VARCHAR(36)  NOT NULL,
    partner_ref   VARCHAR(128),
    final_grade   VARCHAR(16),
    final_value   NUMERIC(12,2) NOT NULL,
    delta         NUMERIC(12,2) NOT NULL,
    note          VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_grading_valuation ON grading_event (tenant_id, valuation_ref);

CREATE TABLE withdrawal_case (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    href          VARCHAR(255),
    party_id      VARCHAR(64),
    order_ref     VARCHAR(64),
    agreement_ref VARCHAR(36)  NOT NULL,
    clock_start   TIMESTAMP WITH TIME ZONE NOT NULL,
    return_grade  VARCHAR(16),
    deduction     NUMERIC(12,2),
    refund_amount NUMERIC(12,2),
    refund_ref    VARCHAR(64),
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_withdrawal_tenant ON withdrawal_case (tenant_id, created_at);
CREATE INDEX idx_withdrawal_agreement ON withdrawal_case (tenant_id, agreement_ref);

CREATE TABLE trade_in_residual (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    device_ref  VARCHAR(128) NOT NULL,
    age_months  INT          NOT NULL,
    base_value  NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(8)   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_residual_device ON trade_in_residual (tenant_id, device_ref, age_months);

CREATE TABLE device_flag (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL,
    imei       VARCHAR(32)  NOT NULL,
    flag       VARCHAR(24)  NOT NULL,
    reason     VARCHAR(64),
    source_ref VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_device_flag_imei ON device_flag (tenant_id, imei);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
