-- Social care: inbound DIRECT MESSAGES pulled from social platforms — private
-- support conversations, scored for sentiment + intent. When a DM needs a human
-- (negative mood or a support ask), insight emits SocialCareTicketRequested and
-- the trouble-ticket service opens a case. Idempotent per (tenant, platform, id).
CREATE TABLE social_dm (
    id               VARCHAR(36)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    platform         VARCHAR(32),
    external_id      VARCHAR(128),
    author           VARCHAR(255),
    handle           VARCHAR(255),
    text             VARCHAR(2000),
    sentiment        VARCHAR(16),
    needs_care       BOOLEAN      NOT NULL DEFAULT FALSE,
    ticket_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_social_dm PRIMARY KEY (id),
    CONSTRAINT uq_social_dm UNIQUE (tenant_id, platform, external_id)
);
CREATE INDEX idx_social_dm_tenant ON social_dm (tenant_id);
