-- CARE CHAT: the customer-facing conversation rail. A session belongs to a
-- tenant and (when authenticated) to the party who opened it; messages are
-- the transcript. The bot answers first; an agent joining flips the session
-- to 'agent' and the bot goes silent — humans outrank models, always.
CREATE TABLE care_chat_session (
    id          VARCHAR(36)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    party_id    VARCHAR(64),                         -- NULL = guest
    channel     VARCHAR(16)  NOT NULL,               -- guest | authed
    status      VARCHAR(16)  NOT NULL DEFAULT 'open',-- open | agent | escalated | closed
    ticket_id   VARCHAR(64),                         -- set on escalation
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_care_chat_session_tenant ON care_chat_session (tenant_id, status, updated_at);
CREATE INDEX idx_care_chat_session_party  ON care_chat_session (tenant_id, party_id);

CREATE TABLE care_chat_message (
    id          VARCHAR(36)  PRIMARY KEY,
    session_id  VARCHAR(36)  NOT NULL REFERENCES care_chat_session (id),
    tenant_id   VARCHAR(64)  NOT NULL,
    author      VARCHAR(16)  NOT NULL,               -- customer | bot | agent
    body        TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_care_chat_message_session ON care_chat_message (session_id, created_at);
