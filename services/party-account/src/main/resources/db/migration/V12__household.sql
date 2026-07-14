-- Household billing: a PERSON can be the payer for another person's products
-- (parent pays for the child's watch plan). Same payer machinery as B2B —
-- identity and money are separate axes. Consent is a two-step: the dependent
-- requests (pending), the payer accepts (active).
ALTER TABLE individual ADD COLUMN household_payer_id VARCHAR(36);
ALTER TABLE individual ADD COLUMN household_status VARCHAR(16);
