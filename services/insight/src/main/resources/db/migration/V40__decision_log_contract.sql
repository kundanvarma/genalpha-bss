-- Every decision cites the learning contract version it ran under (phase 4); null = defaults / no contract.
ALTER TABLE decision_log ADD COLUMN contract VARCHAR(80);
