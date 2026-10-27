-- Benefit Choices (#359): who provides a benefit, where their terms are, and
-- what the operator expects to pay them a month per active benefit (#360's
-- partner cost ledger reads it). The partner sells; the operator pays.
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS choice_partner VARCHAR(500);
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS choice_partner_terms VARCHAR(500);
ALTER TABLE product_offering ADD COLUMN IF NOT EXISTS choice_wholesale_cost NUMERIC(12, 2);
