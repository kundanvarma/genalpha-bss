-- Benefit Choices (#420): a benefit picked once per LINE (a phone's benefit:
-- priority on a line, an upgrade pass per handset) rather than once per
-- household. Null or false: once per household, as before.
ALTER TABLE product_offering ADD COLUMN choice_per_line BOOLEAN;
