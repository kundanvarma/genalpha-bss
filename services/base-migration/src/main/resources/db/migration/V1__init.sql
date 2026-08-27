-- Base migration: moving the installed base without breaking the law or the
-- bills. A migration_plan is the operator's stated intent — source→target
-- offering matrix, eligibility, trigger, jurisdiction pack — and it cannot
-- arm without a simulation receipt. migration_customer is one subscriber's
-- journey through it: notice, exit window, one TMF622 modify order.
CREATE TABLE migration_plan (
    id                   VARCHAR(36)   NOT NULL PRIMARY KEY,
    tenant_id            VARCHAR(64)   NOT NULL,
    href                 VARCHAR(255),
    name                 VARCHAR(255)  NOT NULL,
    state                VARCHAR(16)   NOT NULL,  -- draft|simulated|armed|running|paused|done
    trigger_type         VARCHAR(24)   NOT NULL,  -- bulk|age-threshold|promo-expiry
    matrix_json          VARCHAR(8000) NOT NULL,  -- [{sourceOfferingId,targetOfferingId,characteristicMap,deltaClass}]
    eligibility_json     VARCHAR(4000),           -- {inBinding, segmentExcludes, grandfatherPartyIds}
    trigger_json         VARCHAR(2000),           -- age: {ageYears,strategy}; promo-expiry: {promotionId?,endDate}
    jurisdiction_json    VARCHAR(2000),           -- {noticeDays, exitRightByDeltaClass}
    grandfathered_json   VARCHAR(8000),           -- party ids parked by lapse-to-grandfather
    notice_days          INT           NOT NULL,  -- denormalized from the jurisdiction pack for the gate
    simulation_ref       VARCHAR(64),             -- the rehearsal receipt; arming requires it
    simulation_attached_at TIMESTAMP WITH TIME ZONE,
    max_orders_per_run   INT           NOT NULL,
    breaker_threshold    INT           NOT NULL,  -- consecutive order failures before the wave pauses
    consecutive_failures INT           NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_migration_plan_tenant ON migration_plan (tenant_id, state);

CREATE TABLE migration_customer (
    id                  VARCHAR(36)   NOT NULL PRIMARY KEY,
    tenant_id           VARCHAR(64)   NOT NULL,
    plan_id             VARCHAR(36)   NOT NULL,
    party_id            VARCHAR(64)   NOT NULL,
    product_id          VARCHAR(64)   NOT NULL,
    source_offering_id  VARCHAR(64),
    source_offering_name VARCHAR(255),
    target_offering_id  VARCHAR(64),
    target_offering_name VARCHAR(255),
    delta_class         VARCHAR(16),             -- beneficial|neutral|detrimental
    state               VARCHAR(24)   NOT NULL,  -- scheduled|noticed|exit-window|order-emitted|migrated|exited|failed|rolled-back
    scheduled_for       TIMESTAMP WITH TIME ZONE, -- when the notice may go (binding deferral lands here)
    notice_sent_at      TIMESTAMP WITH TIME ZONE,
    order_ref           VARCHAR(64),
    rollback_order_ref  VARCHAR(64),
    snapshot_json       VARCHAR(8000),           -- pre-migration product state, for the inverse order
    penalty_free_exit   BOOLEAN       NOT NULL,
    exit_right          BOOLEAN       NOT NULL,
    failure_reason      VARCHAR(512),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_migration_customer UNIQUE (tenant_id, plan_id, product_id)
);

CREATE INDEX idx_migration_customer_plan ON migration_customer (tenant_id, plan_id, state);

-- ShedLock-style tick lease: one replica runs the wave at a time.
CREATE TABLE tick_lock (
    name         VARCHAR(64) NOT NULL PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(64) NOT NULL
);

CREATE TABLE event_outbox (
    id         VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    payload    VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
