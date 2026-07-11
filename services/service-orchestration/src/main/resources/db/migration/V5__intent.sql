-- TMF921-shaped intent: what the customer wants to achieve, not how.
-- The OSS answers with feasibility and a service proposal (the report).
CREATE TABLE intent (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    description    VARCHAR(2000),
    owner_party_id VARCHAR(64),
    place          VARCHAR(128) NOT NULL,
    bandwidth_mbps BIGINT NOT NULL,
    latency_ms     BIGINT NOT NULL,
    ai_tokens_millions BIGINT,
    valid_from     TIMESTAMP WITH TIME ZONE,
    valid_until    TIMESTAMP WITH TIME ZONE,
    status         VARCHAR(32)  NOT NULL,
    report         VARCHAR(4000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_intent_tenant ON intent (tenant_id, created_at);
