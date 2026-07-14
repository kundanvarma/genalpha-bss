-- The fourth rules-as-data family: PERSONALIZATION experiences. When a
-- rule's condition matches the visitor's insight context (interests,
-- campaign source, known/unknown), `experience` says what the channel
-- shows — hero category, teaser offering — and `message` is the banner
-- copy. First matching rule wins, exactly like order rules.
ALTER TABLE policy_rule ADD COLUMN experience VARCHAR(2000);
