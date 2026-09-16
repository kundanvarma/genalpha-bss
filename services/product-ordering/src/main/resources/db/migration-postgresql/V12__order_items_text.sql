-- An order's items grew: every item now carries its product's characteristics, lineage and the realizing
-- service the SOM reported, so a business order of a dozen lines passed VARCHAR(4000). Unbounded, like the
-- resource it mirrors.
ALTER TABLE product_order ALTER COLUMN product_order_item TYPE TEXT;
