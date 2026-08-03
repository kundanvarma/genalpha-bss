-- Procedural memory: runbooks PROMOTED from episodic traces — N useful
-- verdicts on one signature draft a candidate; a human approves, edits
-- or rejects; approved runbooks are versioned, carry provenance back to
-- the traces that produced them, and are REVOCABLE. Auto-diagnosis only
-- ever comes from an APPROVED runbook.
CREATE TABLE incident_runbook (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    signature       VARCHAR(160) NOT NULL,
    version         INT NOT NULL DEFAULT 1,
    status          VARCHAR(16) NOT NULL,
    title           VARCHAR(200),
    diagnosis       VARCHAR(2000) NOT NULL,
    action          VARCHAR(1000),
    provenance_json VARCHAR(2000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at      TIMESTAMP WITH TIME ZONE,
    decided_note    VARCHAR(512)
);

CREATE INDEX idx_runbook_signature ON incident_runbook (tenant_id, signature, status);
