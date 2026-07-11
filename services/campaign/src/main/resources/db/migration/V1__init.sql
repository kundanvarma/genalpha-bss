CREATE TABLE campaign (
    id                 VARCHAR(36) PRIMARY KEY,
    href               VARCHAR(255),
    tenant_id          VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    name               VARCHAR(255) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    trigger_event_type VARCHAR(128) NOT NULL,
    trigger_state      VARCHAR(64),
    message_subject    VARCHAR(255) NOT NULL,
    message_content    VARCHAR(2000) NOT NULL,
    promotion_code     VARCHAR(64),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update        TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_campaign_tenant ON campaign (tenant_id);
CREATE INDEX idx_campaign_trigger ON campaign (trigger_event_type);

CREATE TABLE campaign_execution (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    campaign_id VARCHAR(36) NOT NULL,
    party_id    VARCHAR(64) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_campaign_execution_tenant ON campaign_execution (tenant_id);
CREATE UNIQUE INDEX ux_campaign_execution_once ON campaign_execution (tenant_id, campaign_id, party_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
