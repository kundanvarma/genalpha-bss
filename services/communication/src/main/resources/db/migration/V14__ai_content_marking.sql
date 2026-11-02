-- EU AI Act Art. 50(2): a message whose copy a model wrote keeps the
-- machine-readable mark the sender declared (TMF681 characteristics
-- aiGenerated / aiModel), so the inbox, the event and every later read say so.
-- ai_generated is TRUE or empty (never FALSE): the inbox lists by example, and
-- an unmarked message must not be told apart from every row written before.
ALTER TABLE communication_message ADD COLUMN ai_generated BOOLEAN;
ALTER TABLE communication_message ADD COLUMN ai_model VARCHAR(128);
