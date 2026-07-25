-- CREDIT NOTES: the numbered document that reverses part or all of a
-- posted invoice. The wrong invoice is never edited — the kreditnota
-- reverses it, in an UNBROKEN per-tenant sequence, referencing the
-- original bill number. Append-only: corrections of corrections are
-- new credit notes.
CREATE TABLE credit_note (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    credit_note_no  VARCHAR(32) NOT NULL,
    bill_id         VARCHAR(36) NOT NULL,
    bill_no         VARCHAR(64),
    owner_party_id  VARCHAR(64),
    amount_value    NUMERIC(12,2) NOT NULL,
    amount_unit     VARCHAR(8),
    reason          VARCHAR(255) NOT NULL,
    settlement      VARCHAR(16) NOT NULL,
    refund_ref      VARCHAR(64),
    dispute_id      VARCHAR(36),
    issued_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_credit_note_no ON credit_note (tenant_id, credit_note_no);
CREATE INDEX idx_credit_note_bill ON credit_note (tenant_id, bill_id);
CREATE INDEX idx_credit_note_owner ON credit_note (tenant_id, owner_party_id);

-- The gapless series (billing's first sequential counter): one row per
-- (tenant, series), incremented under a row lock in the issuing tx.
CREATE TABLE document_sequence (
    tenant_id  VARCHAR(64) NOT NULL,
    series     VARCHAR(32) NOT NULL,
    next_value BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, series)
);
