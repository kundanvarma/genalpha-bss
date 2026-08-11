-- Bring-your-own CMS/DAM: per-tenant reference-mode content provider.
-- A tenant with a row here serves its imagery FROM an external headless
-- CMS/DAM (Sanity, Strapi, …) instead of the built-in DAM; a tenant with
-- NO row keeps the hosted store (in-row/S3/Azure) — reference mode is
-- strictly opt-in, per operator. One provider per tenant (PK = tenant_id).
--
-- The token is NEVER stored here: secret_ref names an ENV VARIABLE the
-- runtime reads at call time, so a DB dump carries no credentials.
CREATE TABLE content_provider_config (
    tenant_id    VARCHAR(64) PRIMARY KEY,
    provider     VARCHAR(32) NOT NULL,          -- 'sanity', 'http', …
    base_url     VARCHAR(255),                  -- host override (self-host/mock); null = provider default
    project_id   VARCHAR(128),                  -- Sanity projectId / CMS space
    dataset      VARCHAR(128),                  -- Sanity dataset
    secret_ref   VARCHAR(128),                  -- ENV VAR NAME holding the token — not the token
    direct_url   BOOLEAN NOT NULL DEFAULT FALSE,-- 7a: emit CDN url in attachment vs redirect (default redirect)
    config       VARCHAR(2000),                 -- generic HTTP connector JSON (P4): url template, auth, json-path
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update  TIMESTAMP WITH TIME ZONE NOT NULL
);
