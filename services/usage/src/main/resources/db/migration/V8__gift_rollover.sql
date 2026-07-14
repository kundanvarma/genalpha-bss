-- Gifts and rollover ride the boost model: a gift is a matched pair of
-- boosts (negative on the giver, positive on the receiver), rollover is a
-- one-cycle boost minted at month close (the AT&T model, capped at one
-- month's plan allowance like T-Mobile's stash). `source` says which story
-- a boost tells: NULL = purchased top-up, 'gift:...' or 'rollover'.
ALTER TABLE allowance_boost ADD COLUMN source VARCHAR(160);
