-- The country-pluggable national-registry seam (freg-address-plan F-P1):
-- one registry binding per (tenant, country) — the adapter key, the endpoint,
-- and a secret REFERENCE (an env-var name, never the key). No row for a
-- country = no registry there; validation degrades to postal wash.
CREATE TABLE registry_config (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    country      VARCHAR(2)  NOT NULL,
    provider     VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),
    base_url     VARCHAR(255),
    secret_ref   VARCHAR(128),
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_registry_config UNIQUE (tenant_id, country)
);
CREATE INDEX idx_registry_config_tenant ON registry_config (tenant_id);

-- The GDPR ledger: every registry lookup is logged — who asked, about whom,
-- for what purpose, against which country's register, and what came back.
-- Lawful basis and retention differ per country, so the row carries the
-- country. Outcome only — the registry's answer body is never stored here.
CREATE TABLE registry_lookup_log (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    country     VARCHAR(2)  NOT NULL,
    provider    VARCHAR(64) NOT NULL,
    caller_sub  VARCHAR(64),
    party_name  VARCHAR(255),
    purpose     VARCHAR(128) NOT NULL,
    outcome     VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_registry_lookup_tenant ON registry_lookup_log (tenant_id, created_at);
