-- Benefit Choices (#354): the household's pool of Choices.
--
-- A POOL is one per customer party (the household's owner). Its row is the
-- lock every hold takes (SELECT ... FOR UPDATE), so two simultaneous picks
-- against one free Choice serialise: one wins, the other is told why not.
-- A GRANT is what a plan put in (one per order item that bought it).
-- An ALLOCATION is what a benefit took out (one per order item that picked
-- it); the order item is the duplicate key, so a repeated request returns the
-- same allocation instead of spending twice. Free = active grants - held -
-- committed. Choices are counted, never money: no amount column exists.
CREATE TABLE choice_pool (
    tenant_id      VARCHAR(64) NOT NULL,
    id             VARCHAR(36) NOT NULL,
    owner_party_id VARCHAR(64) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_choice_pool PRIMARY KEY (id),
    CONSTRAINT uq_choice_pool_owner UNIQUE (tenant_id, owner_party_id)
);

CREATE TABLE choice_grant (
    tenant_id       VARCHAR(64)  NOT NULL,
    id              VARCHAR(36)  NOT NULL,
    pool_id         VARCHAR(36)  NOT NULL,
    order_id        VARCHAR(64)  NOT NULL,
    order_item_id   VARCHAR(64)  NOT NULL,
    offering_id     VARCHAR(64)  NOT NULL,
    offering_name   VARCHAR(255) NOT NULL,
    choices         INTEGER      NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    granted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_choice_grant PRIMARY KEY (id),
    CONSTRAINT uq_choice_grant_item UNIQUE (tenant_id, order_id, order_item_id),
    CONSTRAINT ck_choice_grant_positive CHECK (choices > 0)
);

CREATE TABLE choice_allocation (
    tenant_id       VARCHAR(64)  NOT NULL,
    id              VARCHAR(36)  NOT NULL,
    pool_id         VARCHAR(36)  NOT NULL,
    order_id        VARCHAR(64)  NOT NULL,
    order_item_id   VARCHAR(64)  NOT NULL,
    offering_id     VARCHAR(64)  NOT NULL,
    offering_name   VARCHAR(255) NOT NULL,
    cost            INTEGER      NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_choice_allocation PRIMARY KEY (id),
    CONSTRAINT uq_choice_allocation_item UNIQUE (tenant_id, order_id, order_item_id),
    CONSTRAINT ck_choice_allocation_positive CHECK (cost > 0)
);

CREATE INDEX ix_choice_grant_pool ON choice_grant (tenant_id, pool_id);
CREATE INDEX ix_choice_allocation_pool ON choice_allocation (tenant_id, pool_id);
CREATE INDEX ix_choice_allocation_order ON choice_allocation (tenant_id, order_id);

CREATE TABLE event_outbox (
    id         VARCHAR(36)    NOT NULL,
    event_type VARCHAR(255)   NOT NULL,
    payload    VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);
