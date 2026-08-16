-- O1 (opportunity, solid) + C1 (CPQ, solid).

-- Opportunity: how long a deal has sat in its stage (aging), and the
-- forecast category a manager commits (distinct from the raw probability).
ALTER TABLE sales_opportunity ADD COLUMN stage_changed_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE sales_opportunity ADD COLUMN forecast_category VARCHAR(16);

-- Activities become TASKS, not just a log: a next step with a due date, an
-- assignee, and an open/done status. Lifecycle beats are logged as 'done'.
ALTER TABLE opportunity_activity ADD COLUMN due_date TIMESTAMP WITH TIME ZONE;
ALTER TABLE opportunity_activity ADD COLUMN status   VARCHAR(16) NOT NULL DEFAULT 'done';
ALTER TABLE opportunity_activity ADD COLUMN assignee VARCHAR(255);
CREATE INDEX idx_opp_task ON opportunity_activity (tenant_id, status, due_date);

-- Line items know recurring vs one-time, so the quote can split MRR from
-- one-off charges.
ALTER TABLE opportunity_item ADD COLUMN recurring BOOLEAN NOT NULL DEFAULT true;

-- The quote carries a one-time total alongside its monthly (recurring) total.
ALTER TABLE quote ADD COLUMN one_time_total NUMERIC(12,2) NOT NULL DEFAULT 0;
