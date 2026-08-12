-- Card orchestration (PSP-P4 reach): route a charge to a provider and order the
-- candidates. `priority` orders the pool (lower is tried first); `currencies` is a
-- JSON array of ISO codes a provider handles (NULL/empty = any currency). The
-- authorize path tries the matching providers in order and fails over past one
-- that is unreachable — a single acquirer outage no longer sinks a charge. Both
-- columns default to "unchanged behaviour": priority 100 and any-currency, so a
-- single-provider tenant resolves exactly as before.
ALTER TABLE payment_provider_config ADD COLUMN priority INT NOT NULL DEFAULT 100;
ALTER TABLE payment_provider_config ADD COLUMN currencies VARCHAR(256);
