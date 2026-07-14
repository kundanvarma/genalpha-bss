-- Ask-to-buy allowance (T-Mobile Family Allowances x Google Family Link):
-- the monthly EUR budget the FAMILY funds for this member's top-ups. Within
-- it, a member's top-up completes instantly on the family bill; above it a
-- child's order HOLDS for approval while an adult falls back to self-pay.
-- NULL = no family funding (adults self-pay; a child asks for everything).
ALTER TABLE individual ADD COLUMN topup_allowance_value NUMERIC(10,2);
