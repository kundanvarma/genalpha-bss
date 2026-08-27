-- RESTRICTION: the lighter enforcement primitive under a nonpayment case —
-- the line stays up (state unchanged) but carries a barring profile:
-- outgoing barred / data throttled, emergency numbers ALWAYS whitelisted.
-- Distinct from suspend: restriction is proportionate first-step enforcement.
ALTER TABLE service ADD COLUMN restricted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE service ADD COLUMN restriction_reason VARCHAR(32);
ALTER TABLE service ADD COLUMN restriction_profile VARCHAR(512);
