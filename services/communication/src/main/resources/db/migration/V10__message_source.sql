-- The originating campaign/journey of a message, so the interaction timeline can
-- show a CSR "from Campaign X" instead of just a bare subject.
ALTER TABLE communication_message ADD COLUMN source VARCHAR(128);
