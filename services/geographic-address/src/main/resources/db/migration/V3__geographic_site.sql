-- TMF674: the first REUSABLE named place. Addresses are flat unnamed rows
-- and places ride orders per-transaction; a site gives a party's premises a
-- name, a status and a stored address — the branch entered once.
CREATE TABLE geographic_site (
    id            VARCHAR(36)   NOT NULL,
    href          VARCHAR(255),
    tenant_id     VARCHAR(64)   NOT NULL,
    name          VARCHAR(255)  NOT NULL,
    description   VARCHAR(1000),
    status        VARCHAR(16)   NOT NULL,
    related_party VARCHAR(2000),
    address_id    VARCHAR(36),
    created_at    TIMESTAMP WITH TIME ZONE,
    last_update   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_geographic_site PRIMARY KEY (id)
);
CREATE INDEX idx_geographic_site_tenant ON geographic_site (tenant_id);
