-- Norway rails, part A (registry sync + directory obligation).
--
-- individual gains the national-registry link and the two compliance flags:
--   registry_person_ref  the stable person id the re-sync worker polls for
--   address_protected    registry returned no address / a protected marker
--                        (kode 6/7 shape): street data is masked EVERYWHERE
--                        and excluded from every export
--   deceased             a registry death event OPENED A FLAG — care flows
--                        decide what happens next, never an automatic
--                        termination
ALTER TABLE individual ADD COLUMN registry_person_ref VARCHAR(64);
ALTER TABLE individual ADD COLUMN address_protected BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE individual ADD COLUMN deceased BOOLEAN DEFAULT FALSE NOT NULL;

-- Per-tenant cursor into the registry's sequential event feed.
CREATE TABLE registry_feed_cursor (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    last_seq BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_registry_cursor_tenant UNIQUE (tenant_id)
);

-- Directory obligation: per party (+ optional serviceRef when the choice is
-- per subscription) — exposure full|partial|reserved; secret_number is the
-- mandatory free service that forces reserved and suppresses everything.
CREATE TABLE directory_setting (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    party_id VARCHAR(36) NOT NULL,
    service_ref VARCHAR(64),
    exposure VARCHAR(16) NOT NULL,
    secret_number BOOLEAN DEFAULT FALSE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_directory_setting UNIQUE (tenant_id, party_id, service_ref)
);

-- The delta export the number-directory agreement requires, kept as an
-- internal audit trail: a run header + the JSON rows actually shipped.
-- Reserved, secret-number and protected-address parties NEVER appear here —
-- that exclusion is the compliance point, and the regulator audits leaks.
CREATE TABLE directory_export_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    ran_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_count INTEGER NOT NULL
);

CREATE TABLE directory_export_row (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    party_id VARCHAR(36) NOT NULL,
    service_ref VARCHAR(64),
    payload VARCHAR(2000) NOT NULL,
    exported_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_directory_row_run ON directory_export_row (run_id);
CREATE INDEX idx_directory_setting_party ON directory_setting (tenant_id, party_id);
