-- Social lead-ads import: the platform's lead id makes the pull
-- idempotent — re-importing the same form never duplicates a lead.
ALTER TABLE sales_lead ADD COLUMN social_ref VARCHAR(128);
ALTER TABLE sales_lead ADD CONSTRAINT uq_lead_social UNIQUE (tenant_id, social_ref);
