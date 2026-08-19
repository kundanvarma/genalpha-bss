-- Tvilling T-P1: the twin rides the signal (it is FICTION — safe to store),
-- and the vault holds what makes it reversible: the per-signal key and the
-- offset map that re-anchors twin-space evidence to the stored text. Destroy
-- the vault row and the twin is permanently unlinkable — which is exactly
-- what erasure does (party_id rides the row for the cascade).
ALTER TABLE customer_signal ADD COLUMN twin_text VARCHAR(4000);

CREATE TABLE twin_vault (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    signal_id   VARCHAR(36) NOT NULL,
    party_id    VARCHAR(64),
    twin_key    VARCHAR(64) NOT NULL,
    offset_map  VARCHAR(4000),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_twin_vault PRIMARY KEY (id),
    CONSTRAINT uq_twin_vault UNIQUE (tenant_id, signal_id)
);
CREATE INDEX idx_twin_vault_party ON twin_vault (tenant_id, party_id);
