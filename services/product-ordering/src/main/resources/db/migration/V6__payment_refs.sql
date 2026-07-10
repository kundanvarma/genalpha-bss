-- TMF622 payment refs: which authorized payments settle this order's
-- one-time charges. Stored verbatim like every reference list.

ALTER TABLE product_order ADD COLUMN payment VARCHAR(2000);
