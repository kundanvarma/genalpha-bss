-- CFS on every sellable spec (catalog-to-provisioning step 1): the service
-- record names the TMF633 customer-facing service it realises, so the TMF638
-- inventory face points at the catalog's real spec instead of a derived one,
-- and the family that decided its fulfilment is a fact on the row.
ALTER TABLE service ADD COLUMN cfs_id VARCHAR(64);
ALTER TABLE service ADD COLUMN cfs_name VARCHAR(160);
ALTER TABLE service ADD COLUMN cfs_family VARCHAR(32);
