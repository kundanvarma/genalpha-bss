-- An offering that cannot say what it costs cannot be sold: link offerings to
-- their productOfferingPrice resources (TMF620 reference list, echoed verbatim).

ALTER TABLE product_offering ADD COLUMN product_offering_price VARCHAR(4000);
