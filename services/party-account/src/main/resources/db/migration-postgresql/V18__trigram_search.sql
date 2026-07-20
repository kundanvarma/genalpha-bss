-- FUZZY CUSTOMER SEARCH: pg_trgm gives typo-tolerant matching ("Solvieg"
-- finds Solveig) with a GIN index so it stays fast at millions of rows.
-- The strict term-AND search is untouched — the trigram path only speaks
-- when strict finds NOTHING. Postgres-only by nature (H2 unit tests keep
-- the plain path; the service falls back gracefully).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_individual_name_trgm ON individual
    USING GIN ((lower(coalesce(given_name,'') || ' ' || coalesce(family_name,''))) gin_trgm_ops);
