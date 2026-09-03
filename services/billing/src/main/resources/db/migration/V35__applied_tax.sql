-- TMF678 appliedTax on a bill line: the VAT percent the CATALOG PRICE declared
-- (TMF620 productOfferingPrice.tax). Null = the tenant's default rate; an explicit
-- 0 = zero-rated (Guyana: residential internet data). Revenue posts per line.
ALTER TABLE applied_billing_rate ADD COLUMN applied_tax_rate NUMERIC(7,3);
