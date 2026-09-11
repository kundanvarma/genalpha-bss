-- The EAP-AKA relay's only per-request state, kept in the database so any
-- instance can finish a challenge another one started (TS.43 §2.8.1: the
-- challenge and the answer are two HTTP requests). Short-lived, swept.
CREATE TABLE eap_session (
    id          VARCHAR(64)  PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    eap_id      VARCHAR(160) NOT NULL,
    imsi        VARCHAR(16)  NOT NULL,
    identifier  INTEGER      NOT NULL,
    xres_hex    VARCHAR(64)  NOT NULL,
    kaut_hex    VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_eap_session_created ON eap_session (created_at);
