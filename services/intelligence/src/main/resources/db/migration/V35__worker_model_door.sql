-- The worker model door (#283): a digital worker calls its model THROUGH the
-- BSS, with a key of its own, instead of holding the tenant's provider key.
-- The key is stored only as a SHA-256 hash; it names the tenant and the worker
-- badge it was issued to, so a call is attributed and a fired worker is cut off.
CREATE TABLE worker_door_key (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL,
    badge        VARCHAR(128) NOT NULL,
    -- the badge's user id (the token subject the workforce ledger uses), so door
    -- spend joins the worker's tasks; null when the issuer did not know it
    worker_id    VARCHAR(64),
    job          VARCHAR(32),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_worker_door_key_hash UNIQUE (key_hash)
);
CREATE INDEX ix_worker_door_key_tenant ON worker_door_key (tenant_id);

-- Who made the call. NULL for the BSS's own copilots (the use case says what
-- they were); a worker's badge for a call through the door.
ALTER TABLE ai_audit ADD COLUMN actor_id VARCHAR(64);
ALTER TABLE ai_audit ADD COLUMN actor_name VARCHAR(128);
