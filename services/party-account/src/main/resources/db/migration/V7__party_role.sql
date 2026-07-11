-- TMF669: the roles a party plays toward the operator (customer, partner,
-- supplier...). Self-registration mints 'customer' automatically.
-- (V6 is the postgres-only RLS migration.)
CREATE TABLE party_role (
    id          VARCHAR(36) PRIMARY KEY,
    href        VARCHAR(255),
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name        VARCHAR(64) NOT NULL,
    party_id    VARCHAR(64) NOT NULL,
    status      VARCHAR(32) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_party_role_tenant ON party_role (tenant_id);
CREATE INDEX idx_party_role_party ON party_role (party_id);
