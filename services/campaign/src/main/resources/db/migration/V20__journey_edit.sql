-- Live journey editing (the Klaviyo/Customer.io model): steps become
-- editable after launch, forward-only. This stamp records that an edit
-- happened so funnel/lift readouts can say "earlier enrollees walked a
-- different version" instead of quietly mixing step versions.
ALTER TABLE journey ADD COLUMN steps_edited_at TIMESTAMP WITH TIME ZONE;
