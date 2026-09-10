-- DECISION LOG (phase 1): every adaptive choice now carries a decision id, so
-- the outcome that follows (a conversion, an adoption) joins back to the
-- context, eligible actions, policy and propensity that produced it.
ALTER TABLE journey_enrollment ADD COLUMN decision_id VARCHAR(36);
ALTER TABLE campaign_execution ADD COLUMN decision_id VARCHAR(36);
ALTER TABLE arbitration_decision ADD COLUMN decision_id VARCHAR(36);
