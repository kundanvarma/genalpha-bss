-- The operator's PSP menu (per tenant): which payment providers a tenant uses,
-- each with its own endpoint, secret-ref and enabled methods. No row = the global
-- (env-configured) PSP — opt-in, like the CMS and carrier seams. The API key is
-- never here: secret_ref names an env var, resolved at call time.
CREATE TABLE payment_provider_config (
    id           VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    provider     VARCHAR(32) NOT NULL,       -- adapter name: 'mock', 'stripe', 'klarna', …
    display_name VARCHAR(64),
    base_url     VARCHAR(255),
    secret_ref   VARCHAR(128),               -- ENV VAR NAME for the key — not the value
    methods      VARCHAR(500),               -- JSON: ["card","klarna","paypal"]
    is_default   BOOLEAN NOT NULL DEFAULT FALSE,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_payment_provider UNIQUE (tenant_id, provider)
);
CREATE INDEX idx_payment_provider_tenant ON payment_provider_config (tenant_id);
