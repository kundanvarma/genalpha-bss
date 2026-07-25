-- The subledger: balanced double-entry postings built from billing and
-- payment events. source_ref is unique per tenant — at-least-once event
-- delivery can never double-book. Lines snapshot account code AND name:
-- remapping the chart later never rewrites booked history.
CREATE TABLE journal_entry (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    entry_date   DATE NOT NULL,
    source_ref   VARCHAR(128) NOT NULL,
    source_type  VARCHAR(32) NOT NULL,
    description  VARCHAR(255),
    currency     VARCHAR(8),
    party_id     VARCHAR(64),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_journal_source ON journal_entry (tenant_id, source_ref);
CREATE INDEX idx_journal_date ON journal_entry (tenant_id, entry_date);

CREATE TABLE journal_line (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    entry_id     VARCHAR(36) NOT NULL,
    seq          INT NOT NULL,
    account_code VARCHAR(32) NOT NULL,
    account_name VARCHAR(128) NOT NULL,
    debit        NUMERIC(14,2) NOT NULL DEFAULT 0,
    credit       NUMERIC(14,2) NOT NULL DEFAULT 0,
    ref          VARCHAR(128),
    description  VARCHAR(255)
);

CREATE INDEX idx_journal_line_entry ON journal_line (tenant_id, entry_id);

-- Posting rules as data: finance's own chart, per tenant.
CREATE TABLE account_mapping (
    tenant_id    VARCHAR(64) NOT NULL,
    mapping_key  VARCHAR(64) NOT NULL,
    account_code VARCHAR(32) NOT NULL,
    account_name VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, mapping_key)
);

-- Transactional outbox (exact shared shape).
CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
