-- Credit-decision port (Norway rails, P3): the DECISION is stored, NEVER the
-- report — decision + score band + a remarks-present boolean + when. A
-- licensed bureau's report never touches our disks; the party's national id
-- itself is not here either (the port takes an opaque reference).
CREATE TABLE credit_decision (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    party_id VARCHAR(36) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    score_band VARCHAR(16),
    remarks_present BOOLEAN DEFAULT FALSE NOT NULL,
    purpose VARCHAR(64),
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_credit_decision_party ON credit_decision (tenant_id, party_id);
