-- Humans read the dashboard: alongside the stable subject id, every ledger
-- row carries the WORKER'S USERNAME as the token presented it — so the
-- crew list says worker-hermes@…, not a UUID.
ALTER TABLE workforce_task ADD COLUMN claimed_by_name VARCHAR(200);
ALTER TABLE workforce_approval ADD COLUMN requested_by_name VARCHAR(200);
ALTER TABLE workforce_approval ADD COLUMN decided_by_name VARCHAR(200);
