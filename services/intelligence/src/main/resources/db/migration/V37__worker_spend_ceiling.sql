-- The workers' own spend ceiling (#283, #307). A busy crew can never spend the
-- copilots' share: workers have a ceiling of their own, over their own window,
-- and their spend ALSO counts toward the tenant's ceiling. 0 = no worker ceiling.
ALTER TABLE ai_budget ADD COLUMN worker_budget_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_budget ADD COLUMN worker_window_hours INTEGER NOT NULL DEFAULT 720;
