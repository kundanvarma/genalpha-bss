-- TMF668: a partnership KIND and the roles it permits. Today an agreement
-- accepts any role string anyone invents; a typed partnership is validated
-- against its type's role list at signature.
CREATE TABLE partnership_type (
    id          VARCHAR(36)   NOT NULL,
    href        VARCHAR(255),
    tenant_id   VARCHAR(64)   NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description VARCHAR(1000),
    status      VARCHAR(16)   NOT NULL,
    role_type   VARCHAR(2000),
    created_at  TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_partnership_type PRIMARY KEY (id)
);
CREATE INDEX idx_partnership_type_tenant ON partnership_type (tenant_id);
