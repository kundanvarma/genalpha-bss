-- TMF620 productOfferingPrice.tax: a price declares the VAT it carries. Empty =
-- the tenant's default rate at posting; an explicit 0 = zero-rated (Guyana:
-- residential internet data). Billing copies it onto the applied rate (TMF678
-- appliedTax); revenue posts the tax line per line.
ALTER TABLE product_offering_price ADD COLUMN tax VARCHAR(1000);
