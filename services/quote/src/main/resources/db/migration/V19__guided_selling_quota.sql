-- C2 guided selling + O2 quota.

-- The guided-selling questionnaire: questions a rep (or an agent) answers.
CREATE TABLE guided_question (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    question_key VARCHAR(64)  NOT NULL,
    prompt       VARCHAR(500) NOT NULL,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_guided_question ON guided_question (tenant_id, sort_order);

-- The rules: an answer to a question recommends an offering (with a quantity).
CREATE TABLE guided_recommendation (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    question_key  VARCHAR(64)  NOT NULL,
    answer_value  VARCHAR(255) NOT NULL,
    offering_name VARCHAR(255) NOT NULL,
    quantity      INTEGER      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_guided_reco ON guided_recommendation (tenant_id, question_key);

-- Sales quotas: a target per owner per period (YYYY-MM).
CREATE TABLE sales_quota (
    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)   NOT NULL,
    owner_name   VARCHAR(255)  NOT NULL,
    quota_period VARCHAR(16)   NOT NULL,
    amount       NUMERIC(14,2) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_sales_quota ON sales_quota (tenant_id, quota_period);
