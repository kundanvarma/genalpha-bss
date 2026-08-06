-- TMF656: problems carry their full spec shape — who declared them
-- (originatorParty), how urgent (priority), why (reason), and what class
-- of declaration (category). Externally-POSTed problems join the
-- alarm-born ones as first-class rows.
ALTER TABLE service_problem ADD COLUMN category VARCHAR(64);
ALTER TABLE service_problem ADD COLUMN priority INTEGER;
ALTER TABLE service_problem ADD COLUMN reason VARCHAR(255);
ALTER TABLE service_problem ADD COLUMN originator_json VARCHAR(1000);
ALTER TABLE service_problem ADD COLUMN affected_services INTEGER;
