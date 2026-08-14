-- Social listening: brand mentions pulled from social platforms, scored for
-- sentiment. The inbound-insight side of social. Idempotent per external id.
CREATE TABLE social_mention (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    platform    VARCHAR(32),
    external_id VARCHAR(128),
    author      VARCHAR(255),
    text        VARCHAR(2000),
    sentiment   VARCHAR(16),
    created_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_social_mention PRIMARY KEY (id),
    CONSTRAINT uq_social_mention UNIQUE (tenant_id, platform, external_id)
);
CREATE INDEX idx_social_mention_tenant ON social_mention (tenant_id);
