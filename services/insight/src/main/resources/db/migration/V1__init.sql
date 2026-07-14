-- Customer insight: the FIRST-PARTY profile — CDP-ready, not a CDP. The
-- enterprise identity graph and event lake live behind the analytics seam;
-- what lives here is the service-context profile the BSS legitimately owns:
-- consent (the spine — nothing is stored without it), browsing interests,
-- campaign attribution, and the visitor->party stitch made at login.
CREATE TABLE visitor_profile (
    id                      VARCHAR(36) PRIMARY KEY,
    tenant_id               VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    visitor_id              VARCHAR(64) NOT NULL,
    -- stitched at login, only under personalization consent
    party_id                VARCHAR(64),
    analytics_consent       BOOLEAN NOT NULL DEFAULT FALSE,
    personalization_consent BOOLEAN NOT NULL DEFAULT FALSE,
    utm_source              VARCHAR(128),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_visitor UNIQUE (tenant_id, visitor_id)
);

CREATE TABLE visitor_event (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    visitor_id  VARCHAR(64) NOT NULL,
    type        VARCHAR(32) NOT NULL,
    category    VARCHAR(128),
    offering_id VARCHAR(64),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_event_visitor ON visitor_event (tenant_id, visitor_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
