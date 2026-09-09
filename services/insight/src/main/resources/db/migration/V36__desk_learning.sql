-- DESK LEARNING (slice 1): the BSS observes how its own desks are used —
-- events, never screens; the staff member is a salted hash; no customer data —
-- and turns friction into suggestions (a preset, a holdout, a stop-field) and
-- into an anonymised export for the vendor's backlog. Per tenant, switchable.
CREATE TABLE desk_event (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    actor_hash  VARCHAR(32) NOT NULL,
    session_id  VARCHAR(64),
    desk        VARCHAR(32) NOT NULL,          -- console | csr | biz | shop-staff
    event       VARCHAR(32) NOT NULL,          -- desk.tabs tab.open form.start form.field form.submit form.abandon search.empty copilot.draft error.shown
    target      VARCHAR(128),                  -- tab path, form name, search box…
    props       TEXT,                          -- JSON: values shape, query, editRatio, tabs…
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_desk_event_tenant_time ON desk_event (tenant_id, occurred_at);

-- an accepted "save a preset" suggestion: pre-filled values for a desk form
CREATE TABLE desk_preset (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    desk        VARCHAR(32) NOT NULL,
    form        VARCHAR(128) NOT NULL,
    name        VARCHAR(160) NOT NULL,
    values_json TEXT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_desk_preset_form ON desk_preset (tenant_id, desk, form);

-- accepted / dismissed suggestions (so a dismissed one stays quiet)
CREATE TABLE desk_decision (
    id            VARCHAR(36) PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    suggestion_id VARCHAR(40) NOT NULL,
    decision      VARCHAR(16) NOT NULL,        -- accepted | dismissed
    decided_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_desk_decision ON desk_decision (tenant_id, suggestion_id);
