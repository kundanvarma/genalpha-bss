-- Per-line credits: which rate lines a credit note reverses, snapshotted.
ALTER TABLE credit_note ADD COLUMN lines_json VARCHAR(4000);
