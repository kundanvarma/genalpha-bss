-- GJ4: saved audiences with a criteria tree — the marketer-friendly
-- replacement for a bare segment string. Evaluated against the consented,
-- stitched profiles this service holds.

CREATE TABLE audience (
    id          VARCHAR(36)  NOT NULL,
    href        VARCHAR(255),
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    name        VARCHAR(255) NOT NULL,
    criteria    VARCHAR(8000),
    created_at  TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_audience PRIMARY KEY (id)
);
CREATE INDEX idx_audience_tenant ON audience (tenant_id);
