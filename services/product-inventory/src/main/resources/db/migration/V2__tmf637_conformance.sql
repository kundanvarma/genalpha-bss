-- TMF637 CTK conformance: a Product carries nested references (productOffering,
-- billingAccount) and collections (productCharacteristic, productPrice,
-- relatedParty), all mandatory in responses. Stored as JSON documents. The
-- former flat id columns are replaced by the nested references.

ALTER TABLE product ADD COLUMN product_offering VARCHAR(4000);
ALTER TABLE product ADD COLUMN billing_account VARCHAR(4000);
ALTER TABLE product ADD COLUMN product_characteristic VARCHAR(4000);
ALTER TABLE product ADD COLUMN product_price VARCHAR(4000);
ALTER TABLE product ADD COLUMN related_party VARCHAR(4000);

ALTER TABLE product DROP COLUMN product_offering_id;
ALTER TABLE product DROP COLUMN billing_account_id;
