-- THE PARTNER API: a chain's own POS is a MACHINE identity — the
-- agreement row names the OAuth2 client that speaks for the chain, so
-- the credential picks the dealer the same way a clerk's org membership
-- does. And the chain sells ITS OWN phones from ITS OWN stock: only the
-- subscription enters our BSS — the device rides along as CONTEXT on
-- the commission entry (attribution, support), never as a billable item.
ALTER TABLE dealer_agreement ADD COLUMN client_id VARCHAR(64);
CREATE INDEX idx_dealer_client ON dealer_agreement (tenant_id, client_id);
ALTER TABLE commission_entry ADD COLUMN device_note VARCHAR(255);
