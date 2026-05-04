-- Persist async job state so server restart doesn't black-hole in-flight
-- ingest / deliberation work. Two tables, one per job type. Both use JSONB
-- for the structured payloads (agent envelopes, ingest result) so the wire
-- shape and the storage shape stay 1:1 — no extra mapping table.
--
-- Retention is enforced at the application layer (jobs.retention-after-
-- completion); the watchdog deletes terminal rows older than the cutoff.
-- Non-terminal rows surviving a restart get rewritten as FAILED on boot
-- so polling clients see "interrupted by server restart" rather than 404.

CREATE TABLE ask_jobs (
    job_id          UUID         PRIMARY KEY,
    document_id     UUID         NOT NULL,
    query           TEXT         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL,
    final_response  TEXT,
    error           TEXT,
    -- AgentEnvelope records serialised as JSONB. Each may be null until the
    -- corresponding agent has run; reading happens at API boundaries via
    -- Jackson, no DB-side queries against these structures.
    proposer        JSONB,
    critic          JSONB,
    synthesiser     JSONB
);

-- Watchdog hits this often; index on the columns it filters by.
CREATE INDEX ask_jobs_terminal_completed_at
    ON ask_jobs (status, completed_at)
    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED');

CREATE TABLE ingest_jobs (
    job_id            UUID         PRIMARY KEY,
    source_path       TEXT         NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    phase             VARCHAR(32)  NOT NULL,
    percent_complete  INTEGER      NOT NULL DEFAULT 0,
    message           TEXT,
    document_id       UUID,
    title             TEXT,
    started_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    error             TEXT,
    -- IngestService.IngestResult serialised as JSONB; only present when
    -- status = COMPLETED. Saves the polling client a follow-up call.
    result            JSONB
);

CREATE INDEX ingest_jobs_terminal_completed_at
    ON ingest_jobs (status, completed_at)
    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED');
