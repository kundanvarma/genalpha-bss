-- Family roles on the household link (Verizon-style): the payer is the OWNER
-- (implicit — no self-link), a dependent is 'member' (consenting adult),
-- 'admin' (co-parent promoted by the owner, may manage the family) or
-- 'child' (payer-created account). Admin is about MANAGEMENT, not money —
-- promoting someone never moves a payer stamp.
ALTER TABLE individual ADD COLUMN household_role VARCHAR(16);
UPDATE individual SET household_role = 'member' WHERE household_payer_id IS NOT NULL;
