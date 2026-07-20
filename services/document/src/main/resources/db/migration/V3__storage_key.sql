-- External content stores (S3-protocol, Azure Blob): the row keeps the
-- METADATA and the storage key; the bytes live where object storage
-- lives. In-row remains the default — content stays NOT-NULL there by
-- construction, just no longer by constraint.
ALTER TABLE document ALTER COLUMN content DROP NOT NULL;
ALTER TABLE document ADD COLUMN storage_key VARCHAR(255);
