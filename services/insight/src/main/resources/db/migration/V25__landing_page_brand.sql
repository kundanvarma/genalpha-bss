-- Landing-page customization: logo, hero image, brand accent colour, an optional
-- secondary link (e.g. "learn more"), and a privacy-policy link for the footer.
-- So a campaign page carries the operator's (or the campaign's) own look.
ALTER TABLE landing_page ADD COLUMN logo_url       VARCHAR(512);
ALTER TABLE landing_page ADD COLUMN hero_image_url VARCHAR(512);
ALTER TABLE landing_page ADD COLUMN brand_color    VARCHAR(16);
ALTER TABLE landing_page ADD COLUMN cta_url        VARCHAR(512);
ALTER TABLE landing_page ADD COLUMN privacy_url    VARCHAR(512);
