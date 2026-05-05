-- Make the parser-invented vs document-owned distinction first-class on the
-- sections table, mirroring the existing chapters.is_synthetic. Drives the
-- domain-layer StructuralRef helper that gates every render boundary
-- (prompt assembly, REST DTOs) against leaking parser-internal labels into
-- LLM input or API output.
--
-- Backfill: the previous SectionDetector synthetic-section title was the
-- literal string "Body". Any pre-V5 row with that exact title was a
-- parser fallback (empty-chapter / no-detected-headings case), so it gets
-- the flag retroactively and its title is rewritten to the new sentinel
-- so it surfaces obviously if any read path bypasses the boundary.
--
-- Same treatment for chapters: pre-V5 synthetic chapters were already
-- flagged is_synthetic=true (V1 schema had the column) but were stored
-- with the literal title "Document". Rewrite those to the chapter sentinel
-- so the rule is uniform — synthetic title in the DB == magic sentinel,
-- never a plausible-looking name.

ALTER TABLE sections
    ADD COLUMN is_synthetic BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE sections
SET is_synthetic = TRUE,
    title = '__SYNTHETIC_HEAP__'
WHERE title = 'Body';

UPDATE chapters
SET title = '__SYNTHETIC_SEGMENT__'
WHERE is_synthetic = TRUE
  AND title = 'Document';
