-- TMF620: category refs on an offering — channels group and filter by these
-- (My plan vs add-ons, like-for-like plan changes).
ALTER TABLE product_offering ADD COLUMN category VARCHAR(4000);
