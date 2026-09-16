-- TMF620 fields the catalog did not yet keep, so a configurable product can be
-- modelled whole with the standard's own attributes:
--   price.validFor            a price has its own window (effective-dated segments)
--   price.unitOfMeasure       "per seat", "per 5 GB" — quantity pricing
--   price.pricingLogicAlgorithm  a named, documented algorithm (tiers, per-unit-above)
--   offering.productOfferingRelationship  requires / excludes / exchangableTo
ALTER TABLE product_offering_price ADD COLUMN valid_from TIMESTAMP WITH TIME ZONE;
ALTER TABLE product_offering_price ADD COLUMN valid_to TIMESTAMP WITH TIME ZONE;
ALTER TABLE product_offering_price ADD COLUMN unit_of_measure VARCHAR(400);
ALTER TABLE product_offering_price ADD COLUMN pricing_logic_algorithm VARCHAR(4000);
ALTER TABLE product_offering ADD COLUMN product_offering_relationship VARCHAR(8000);
