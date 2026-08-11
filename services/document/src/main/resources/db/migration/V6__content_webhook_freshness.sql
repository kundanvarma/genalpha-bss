-- Reference-mode freshness (P3). An external CMS tells us when an asset changes
-- via a webhook: a delete makes the referencing document 'unavailable' (the read
-- path serves the placeholder instead of 302-ing to a now-404 CDN url); an
-- upsert restores it and bumps a version that cache-busts the delivered url.
-- Hosted documents are always available (default true), version 0.
ALTER TABLE document ADD COLUMN available BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE document ADD COLUMN content_version INTEGER NOT NULL DEFAULT 0;

-- The webhook is HMAC-verified with a per-tenant shared secret, named (like the
-- write token) by a secret-ref env var — never the value.
ALTER TABLE content_provider_config ADD COLUMN webhook_secret_ref VARCHAR(128);
