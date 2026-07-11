-- The PSP's capture/refund reference — closes the reconciliation loop
-- (which settlement moved this money) and proves capture actually ran.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS settlement_ref VARCHAR(64);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS psp_provider VARCHAR(32);
