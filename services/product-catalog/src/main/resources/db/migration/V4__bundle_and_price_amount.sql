-- Bundled offerings (TMF620 isBundle/bundledProductOffering) and priced
-- amounts (Money + recurring charge period) — needed to model a triple-play
-- bundle end-to-end rather than describing it in free text.

ALTER TABLE product_offering ADD COLUMN is_bundle BOOLEAN;
ALTER TABLE product_offering ADD COLUMN bundled_product_offering VARCHAR(4000);

ALTER TABLE product_offering_price ADD COLUMN price VARCHAR(1000);
ALTER TABLE product_offering_price ADD COLUMN recurring_charge_period_type VARCHAR(255);
ALTER TABLE product_offering_price ADD COLUMN recurring_charge_period_length INTEGER;
