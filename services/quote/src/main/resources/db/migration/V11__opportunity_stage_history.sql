-- Every stage a deal entered, and when — the record real funnel analytics
-- needs: stage-to-stage conversion, time-in-stage, and cycle time. One row
-- per transition (qualified in, moved, closed).
CREATE TABLE opportunity_stage_history (
    id             VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    opportunity_id VARCHAR(36)  NOT NULL,
    stage          VARCHAR(32)  NOT NULL,
    entered_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_opp_stage_hist ON opportunity_stage_history (tenant_id, opportunity_id, entered_at);
