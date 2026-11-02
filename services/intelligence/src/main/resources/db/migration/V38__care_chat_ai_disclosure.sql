-- EU AI Act Art. 50(1), applies since 2 Aug 2026 (#422): a customer talking to
-- the care chat is told it is an AI before any answer. The session records when.
ALTER TABLE care_chat_session ADD COLUMN ai_disclosed_at TIMESTAMP WITH TIME ZONE;
