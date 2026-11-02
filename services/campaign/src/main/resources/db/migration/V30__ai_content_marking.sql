-- EU AI Act Art. 50(2): content a model wrote is marked in a machine-readable
-- way. A campaign remembers that its message came from the AI copy assistant,
-- and which model wrote it, so every send carries the mark to communication.
ALTER TABLE campaign ADD COLUMN ai_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE campaign ADD COLUMN ai_model VARCHAR(128);
