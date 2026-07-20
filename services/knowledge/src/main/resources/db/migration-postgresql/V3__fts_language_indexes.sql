-- LANGUAGE-AWARE full-text: one GIN expression index per language the
-- fleet speaks, matching the query expression exactly so the planner
-- uses them. Norwegian first-class: "regning" must find "regningene".
CREATE INDEX idx_article_fts_en ON article USING GIN (
    to_tsvector('english', title || ' ' || body || ' ' || coalesce(tags, '')));
CREATE INDEX idx_article_fts_no ON article USING GIN (
    to_tsvector('norwegian', title || ' ' || body || ' ' || coalesce(tags, '')));
