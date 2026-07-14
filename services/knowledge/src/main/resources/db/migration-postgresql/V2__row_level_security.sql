-- Row-Level Security, the second lock: the runtime role below cannot read
-- or write another tenant's rows even if application code loses a tenant
-- predicate. Flyway keeps running as the owning role. Postgres-only
-- migration: H2 test runs skip this version (vendor location).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'knowledge_app') THEN
        CREATE ROLE knowledge_app LOGIN PASSWORD 'knowledge_app';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO knowledge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO knowledge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO knowledge_app;

ALTER TABLE article ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON article
    USING (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (current_setting('app.tenant_id', true) = '__system__'
           OR tenant_id = current_setting('app.tenant_id', true));

-- Full-text search over the library: title, body and tags in one vector.
CREATE INDEX idx_article_fts ON article
    USING GIN (to_tsvector('english', title || ' ' || body || ' ' || coalesce(tags, '')));
