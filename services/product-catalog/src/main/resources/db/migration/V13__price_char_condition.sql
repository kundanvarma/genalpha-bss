-- Characteristic-conditioned pricing (TMF620 prodSpecCharValueUse): a price
-- component that applies only when the configured characteristics match
-- (e.g. "+2.00/month when color = Titanium Edition"). One offering, one
-- spec — price variation without an SKU per variant.
ALTER TABLE product_offering_price ADD COLUMN prod_spec_char_value_use VARCHAR(2000);
