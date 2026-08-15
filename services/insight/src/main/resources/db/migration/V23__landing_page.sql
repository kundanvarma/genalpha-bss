-- Campaign landing pages — a standalone acquisition surface (not the storefront)
-- that ads/emails deep-link to. A consented form submit becomes a prospect
-- stamped with the page's campaign source, closing the acquisition loop.
CREATE TABLE landing_page (
    id         VARCHAR(36)  NOT NULL,
    tenant_id  VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    slug       VARCHAR(128) NOT NULL,
    headline   VARCHAR(255),
    subhead    VARCHAR(2000),
    cta_label  VARCHAR(128),
    utm_source VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_landing_page PRIMARY KEY (id),
    CONSTRAINT uq_landing_page UNIQUE (tenant_id, slug)
);
CREATE INDEX idx_landing_page_tenant ON landing_page (tenant_id);
