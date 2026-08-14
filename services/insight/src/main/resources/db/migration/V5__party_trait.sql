-- BSS-native audiences: a first-party CUSTOMER feature store, projected from the
-- BSS's own operational events. Audiences resolve against these traits directly
-- — no export to a marketing tool, no reverse-ETL round-trip. One row per
-- (party, key, value) so multi-valued traits (several products held) are natural.

CREATE TABLE party_trait (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    party_id    VARCHAR(64)  NOT NULL,
    trait_key   VARCHAR(64)  NOT NULL,
    trait_value VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_party_trait PRIMARY KEY (id),
    CONSTRAINT uq_party_trait UNIQUE (tenant_id, party_id, trait_key, trait_value)
);
CREATE INDEX idx_party_trait_tenant ON party_trait (tenant_id);
CREATE INDEX idx_party_trait_lookup ON party_trait (tenant_id, party_id);
CREATE INDEX idx_party_trait_key ON party_trait (tenant_id, trait_key);
