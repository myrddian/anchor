-- Embedding of the document's top-level summary so that:
--   * GET /documents/search can return docs ranked by cosine relevance
--     to the caller's query (no chunk fan-out, single vector lookup)
--   * POST /validate/quick can compute a vector-only stance score
--     (cos(query, summary) − cos(not-query, summary)) without the
--     deliberation cost — useful as a pre-filter at scale.
--
-- Nullable so existing documents survive the migration; the
-- SummaryEmbeddingBackfill ApplicationRunner fills them on startup.

ALTER TABLE documents
    ADD COLUMN summary_embedding vector(768);

-- HNSW index for the search path. Same operator class as chunks
-- (vector_cosine_ops) so the cosine semantics line up.
CREATE INDEX idx_documents_summary_embedding
    ON documents USING hnsw (summary_embedding vector_cosine_ops);
