-- Benefit Choices (#357): a benefit may hold its Choice for some months (a
-- subsidised device offer). The law of the tenant's country caps what applies
-- (benefit-allowance's HoldLaw); the catalog says only what the offer asks.
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS choice_hold_months INTEGER;
