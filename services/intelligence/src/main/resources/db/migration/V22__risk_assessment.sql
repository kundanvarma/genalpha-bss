-- TMF696: an assessment is a fact a dispute may need later. The score's
-- evidence rides in the row (result JSON echoes every signal), so any
-- assessment can be recomputed by hand from its own body.
CREATE TABLE risk_assessment (
    id          VARCHAR(36)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    party_id    VARCHAR(64)  NOT NULL,
    kind        VARCHAR(24)  NOT NULL,
    score       INTEGER      NOT NULL,
    risk_level  VARCHAR(8)   NOT NULL,
    result      VARCHAR(6000),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_risk_assessment PRIMARY KEY (id)
);
CREATE INDEX idx_risk_assessment_tenant ON risk_assessment (tenant_id, created_at);
CREATE INDEX idx_risk_assessment_party ON risk_assessment (party_id);
