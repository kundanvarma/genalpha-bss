-- Payday alignment: the customer picks the day their billing cycle
-- starts (1-28 — days 29-31 do not exist in February). Null = calendar
-- month, the historical default.
ALTER TABLE individual ADD COLUMN billing_anchor_day INT;
