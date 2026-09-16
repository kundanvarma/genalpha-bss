-- Stepped overage: beyond the included allowance, each slice of usage may carry its own unit price
-- (the tier table lives in the BSS as the price DEFINITION; the OCS seam pushes the same table at activation).
ALTER TABLE usage_allowance ADD COLUMN tier_json VARCHAR(2000);
