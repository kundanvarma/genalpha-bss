-- The field-service seam: a tenant chooses WHO answers "when can an installer
-- come?" — the built-in roster, or its own workforce-management system spoken
-- to over TMF646 (the API TM Forum's Workforce Management component, TMFC046,
-- exposes). Bookings made elsewhere keep the external reference.
ALTER TABLE schedule_config ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'roster';
ALTER TABLE schedule_config ADD COLUMN provider_url VARCHAR(500);
ALTER TABLE schedule_config ADD COLUMN provider_secret_ref VARCHAR(128);
ALTER TABLE schedule_config ADD COLUMN provider_category VARCHAR(64);

ALTER TABLE appointment ADD COLUMN provider VARCHAR(32);
ALTER TABLE appointment ADD COLUMN external_id VARCHAR(128);
