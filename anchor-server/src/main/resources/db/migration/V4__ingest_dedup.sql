-- Lift the in-flight ingest dedup out of the per-process ConcurrentHashMap
-- and into Postgres so two replicas pointed at the same DB don't both ingest
-- the same upload. Old impl was documented as v0-acceptable; this closes it.
--
-- Approach: store the SHA-256 content hash on the ingest_jobs row, and
-- enforce a unique partial index that only applies while the job is in
-- flight (status NOT IN terminal). Terminal rows are free to share hashes
-- — re-ingesting the same file later mints a new row + replaces the
-- document via the existing cascade-delete-then-insert path.
--
-- Conflict handling lives in the application layer (IngestJobRunner): try
-- to insert, catch DataIntegrityViolationException, look up the active job
-- by hash, return that. One round trip in the happy path.

ALTER TABLE ingest_jobs ADD COLUMN content_hash TEXT;

CREATE UNIQUE INDEX ingest_jobs_active_hash
    ON ingest_jobs (content_hash)
    WHERE content_hash IS NOT NULL
      AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- Plain index for the lookup-by-hash query the runner does after a
-- conflict. Cheap, kept narrow.
CREATE INDEX ingest_jobs_content_hash
    ON ingest_jobs (content_hash)
    WHERE content_hash IS NOT NULL;
