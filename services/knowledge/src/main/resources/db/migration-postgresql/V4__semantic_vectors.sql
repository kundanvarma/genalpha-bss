-- THE SEMANTIC NET: pgvector embeddings beside the articles they index.
-- Untyped vector column (providers differ in dimension); exact cosine
-- scan is instant at article-library scale — an HNSW index with a fixed
-- dimension is the documented next step if a library grows huge.
-- Postgres-only by nature; the entity never maps this column, so H2
-- unit tests are untouched.
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE article ADD COLUMN embedding vector;
