-- Scale: equality audiences filter WHERE tenant_id AND trait_key AND trait_value
-- (product = X, loyaltyTier = gold). idx_party_trait_key covers (tenant, key) but
-- not the value, so a popular key still scans its rows. This composite makes the
-- whole equality leaf an index-only lookup — flat as the trait store grows.
CREATE INDEX idx_party_trait_kv ON party_trait (tenant_id, trait_key, trait_value);
