-- TMF620 productOfferingTerm: commitment periods on an offering (e.g. a
-- 12-month binding), echoed verbatim like every other TMF substructure.
ALTER TABLE product_offering ADD COLUMN product_offering_term VARCHAR(4000);
