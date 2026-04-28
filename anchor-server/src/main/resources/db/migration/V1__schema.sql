-- Anchor v0 schema. See SPEC.md §3.2.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    source_path TEXT NOT NULL,
    doc_summary TEXT NOT NULL,
    doc_summary_source VARCHAR(20) NOT NULL,  -- 'AUTHOR_ABSTRACT' | 'GENERATED'
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE chapters (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    title TEXT,
    summary TEXT NOT NULL,
    is_synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (document_id, ordinal)
);

CREATE TABLE sections (
    id UUID PRIMARY KEY,
    chapter_id UUID NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    title TEXT,
    summary TEXT NOT NULL,
    UNIQUE (chapter_id, ordinal)
);

CREATE TABLE paragraphs (
    id UUID PRIMARY KEY,
    section_id UUID NOT NULL REFERENCES sections(id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    raw_text TEXT NOT NULL,
    summary TEXT NOT NULL,
    UNIQUE (section_id, ordinal)
);

CREATE TABLE chunks (
    id UUID PRIMARY KEY,
    paragraph_id UUID NOT NULL REFERENCES paragraphs(id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    text TEXT NOT NULL,
    embedding vector(768) NOT NULL,
    UNIQUE (paragraph_id, ordinal)
);

CREATE INDEX idx_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunks_paragraph ON chunks (paragraph_id);
CREATE INDEX idx_paragraphs_section ON paragraphs (section_id);
CREATE INDEX idx_sections_chapter ON sections (chapter_id);
CREATE INDEX idx_chapters_document ON chapters (document_id);
