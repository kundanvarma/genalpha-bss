-- One event may speak to several people (a data gift has two ends, an
-- ask-to-buy tells requester AND payer): idempotency keys grow a
-- '#<recipient>' suffix, so the column outgrows a bare UUID.
ALTER TABLE communication_message ALTER COLUMN source_event_id TYPE VARCHAR(64);
