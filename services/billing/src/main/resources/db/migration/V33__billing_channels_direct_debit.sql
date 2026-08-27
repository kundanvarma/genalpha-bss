-- BILL CHANNELS + DIRECT DEBIT: the consent-driven per-party channel chain
-- (e-invoice rail -> digital mailbox -> the existing print/partner path) and
-- the bank-side direct-debit loop (mandate file in, claims out, settlement
-- file back). Consent is a ROW per party+channel; resolution happens per
-- send, so a revoked alias falls to the next channel by itself.
CREATE TABLE party_billing_channel (
    id          VARCHAR(36)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    party_id    VARCHAR(64)  NOT NULL,
    channel     VARCHAR(16)  NOT NULL,
    alias_ref   VARCHAR(128),
    consent_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_party_billing_channel UNIQUE (tenant_id, party_id, channel)
);
CREATE INDEX idx_party_billing_channel_party ON party_billing_channel (tenant_id, party_id);

-- The mandate is the BANK's word (it arrives in a batch file, never typed
-- into a console) that this party's bills may be claimed by direct debit.
CREATE TABLE direct_debit_mandate (
    id            VARCHAR(36)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    party_id      VARCHAR(64)  NOT NULL,
    account_ref   VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at  TIMESTAMP WITH TIME ZONE,
    last_update   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_direct_debit_mandate UNIQUE (tenant_id, party_id)
);

-- One claim per bill, ever: the cycle run skips bills already claimed, and
-- the settlement file marks the claim settled by the same KID it went out on.
CREATE TABLE direct_debit_claim (
    id           VARCHAR(36)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    mandate_id   VARCHAR(36)  NOT NULL,
    bill_id      VARCHAR(36)  NOT NULL,
    bill_no      VARCHAR(64)  NOT NULL,
    kid          VARCHAR(64)  NOT NULL,
    amount_value NUMERIC(12,2) NOT NULL,
    amount_unit  VARCHAR(8),
    status       VARCHAR(16)  NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_direct_debit_claim_bill UNIQUE (tenant_id, bill_id)
);
CREATE INDEX idx_direct_debit_claim_kid ON direct_debit_claim (tenant_id, kid);

-- The channel a bill actually left on — the audit answer to "how was this
-- customer invoiced?", next to the delivery ledger's per-attempt story.
ALTER TABLE customer_bill ADD COLUMN distribution_channel VARCHAR(16);
