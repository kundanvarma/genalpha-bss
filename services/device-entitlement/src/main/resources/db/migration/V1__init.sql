-- device-entitlement: the GSMA TS.43 Entitlement Configuration Server's own
-- state. Subscribers are BINDINGS (IMSI ↔ line ↔ plan) the BSS writes; the
-- decisions (VoLTE / VoWiFi / SMSoIP / data plan / eSIM ODSA) are computed
-- from the catalog on every device request and never stored as truth.

CREATE TABLE entitlement_subscriber (
    id                          VARCHAR(64)  PRIMARY KEY,
    tenant_id                   VARCHAR(64)  NOT NULL,
    imsi                        VARCHAR(16)  NOT NULL,
    msisdn                      VARCHAR(20),
    iccid                       VARCHAR(24),
    party_id                    VARCHAR(64),
    service_id                  VARCHAR(64),
    offering_id                 VARCHAR(64),
    status                      VARCHAR(16)  NOT NULL DEFAULT 'active',
    ims_provisioned             BOOLEAN      NOT NULL DEFAULT TRUE,
    emergency_address_confirmed BOOLEAN      NOT NULL DEFAULT FALSE,
    terms_accepted              BOOLEAN      NOT NULL DEFAULT FALSE,
    feature_overrides           VARCHAR(2000),
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update                 TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_entitlement_subscriber_imsi UNIQUE (tenant_id, imsi)
);
CREATE INDEX ix_entitlement_subscriber_party ON entitlement_subscriber (tenant_id, party_id);
CREATE INDEX ix_entitlement_subscriber_service ON entitlement_subscriber (tenant_id, service_id);

CREATE TABLE entitlement_device (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    terminal_id   VARCHAR(64)  NOT NULL,
    imsi          VARCHAR(16),
    vendor        VARCHAR(64),
    model         VARCHAR(64),
    sw_version    VARCHAR(64),
    notif_token   VARCHAR(512),
    notif_action  INTEGER,
    last_apps     VARCHAR(256),
    last_seen_at  TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_entitlement_device UNIQUE (tenant_id, terminal_id)
);

CREATE TABLE entitlement_token (
    token        VARCHAR(96)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    imsi         VARCHAR(16)  NOT NULL,
    terminal_id  VARCHAR(64),
    issued_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_entitlement_token_imsi ON entitlement_token (tenant_id, imsi);

CREATE TABLE companion_device (
    id                    VARCHAR(64)  PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    imsi                  VARCHAR(16)  NOT NULL,
    companion_terminal_id VARCHAR(64)  NOT NULL,
    eid                   VARCHAR(40),
    iccid                 VARCHAR(24),
    vendor                VARCHAR(64),
    model                 VARCHAR(64),
    status                VARCHAR(16)  NOT NULL,
    activation_code       VARCHAR(256),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_companion_device UNIQUE (tenant_id, imsi, companion_terminal_id)
);

CREATE TABLE subscription_transfer (
    id                  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    imsi                VARCHAR(16)  NOT NULL,
    old_terminal_id     VARCHAR(64),
    target_terminal_id  VARCHAR(64),
    target_eid          VARCHAR(40),
    new_iccid           VARCHAR(24),
    status              VARCHAR(16)  NOT NULL,
    activation_code     VARCHAR(256),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE ecs_request (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    terminal_id  VARCHAR(64),
    imsi         VARCHAR(16),
    app          VARCHAR(64),
    operation    VARCHAR(48),
    outcome      VARCHAR(32)  NOT NULL,
    detail       VARCHAR(1000),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ecs_request_created ON ecs_request (tenant_id, created_at);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
