-- Where a service is delivered from (fibre route, edge site). The
-- self-healing story pivots on this column: assurance re-homes a slice
-- by changing it.
ALTER TABLE service ADD COLUMN delivery_path VARCHAR(128);
