-- PUKs move from plaintext to AES-256-GCM ciphertext ("enc:v1:" + base64 of
-- IV||ciphertext) — the column grows to hold it. Legacy plaintext rows remain
-- readable and are upgraded on their next write.
ALTER TABLE sim_card ALTER COLUMN puk TYPE VARCHAR(128);
