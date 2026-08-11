-- The operator's carrier MENU: which carriers a tenant offers, each with its own
-- endpoint, secret-ref and supported delivery methods. Several per tenant (Helthjem
-- + Bring + …); one may be the default. A tenant with NO rows falls back to the
-- global (env-configured) carrier — reference mode is opt-in, like the CMS seam.
-- The token is never stored here: secret_ref names an env var, resolved at call time.
CREATE TABLE carrier_config (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    carrier      VARCHAR(32) NOT NULL,       -- adapter name: 'helthjem', 'bring', …
    display_name VARCHAR(64),                -- what the shop shows: 'Helthjem', 'Posten/Bring'
    base_url     VARCHAR(255),               -- carrier API host (self/mock override)
    secret_ref   VARCHAR(128),               -- ENV VAR NAME for the API key — not the value
    methods      VARCHAR(500),               -- JSON: ["home","pickupPoint","locker"]
    config       VARCHAR(2000),              -- JSON: service codes etc.
    is_default   BOOLEAN NOT NULL DEFAULT FALSE,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_carrier_config UNIQUE (tenant_id, carrier)
);
CREATE INDEX idx_carrier_config_tenant ON carrier_config (tenant_id);
