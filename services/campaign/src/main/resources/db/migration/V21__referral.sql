-- G1 — MEMBER-GET-MEMBER: the research-backed growth channel (referred
-- customers ~16% more valuable, ~18% less likely to churn). A code belongs
-- to a REFERRER; a conversion is one JOINER redeeming it, rewarded in DATA
-- on both sides when the joiner's first order completes — held, not paid,
-- when the velocity guard smells abuse.
CREATE TABLE referral_code (
    code              VARCHAR(16)  NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    referrer_party_id VARCHAR(64)  NOT NULL,
    club_org_id       VARCHAR(64),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_referral_code PRIMARY KEY (tenant_id, code)
);
CREATE UNIQUE INDEX uq_referral_referrer ON referral_code (tenant_id, referrer_party_id);

CREATE TABLE referral_conversion (
    id                VARCHAR(36)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    code              VARCHAR(16)  NOT NULL,
    referrer_party_id VARCHAR(64)  NOT NULL,
    joiner_party_id   VARCHAR(64)  NOT NULL,
    -- pending (awaiting first completed order) | rewarded | held (velocity guard)
    status            VARCHAR(16)  NOT NULL DEFAULT 'pending',
    reward_gb         NUMERIC(6,2),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    rewarded_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_referral_joiner UNIQUE (tenant_id, joiner_party_id)
);
CREATE INDEX idx_referral_conv ON referral_conversion (tenant_id, code);
