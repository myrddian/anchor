# Anchor — v0 Specification

*Working name. Project as described in `document-as-agent-primitive.md` (2026-04-26).*

*Status: draft spec for vibe-coded MVP. Spring Boot service exposing source-grounded chunk validation as a primitive.*

---

## 1. Goals and non-goals

### 1.1 Core thesis

Anchor exposes **one primitive — source-grounded chunk validation — through two interfaces, each optimised for a different consumer**:

- **`/validate` — the LLM-to-LLM interface.** Structured JSON judgment about whether a chunk is load-bearing for a query, given the document's full argument. Synchronous, deterministic enums, machine-parseable. Consumed by other LLMs, agentic research loops, automated fact-checkers, citation validators.
- **`/ask` — the human-facing interface.** A three-agent deliberation (proposer / critic / synthesiser) producing first-person prose response with visible reasoning, grounded in the document's hierarchical self-summary. Async, SSE-streamed, transparent. Consumed by researchers, analysts, anyone reading a paper who needs to trust the conclusion.

These are not "primary and secondary" features. They serve **different cognitive modes**: machines branch on enums; humans read prose and need to see reasoning to trust conclusions. The same hierarchy backs both. The deliberation in `/ask` is not theatre — it is the correct architecture for human-readable output, because humans require visible reasoning to trust grounded answers in a way LLMs do not.

The strategic value of the project is demonstrating that *retrieval can be opinionated about its consumer*. Vanilla RAG returns chunks; downstream code figures out what to do with them. Anchor returns either structured judgment or first-person reasoning, depending on who's asking, and refuses to pretend a chunk speaks for its document.

### 1.2 Goals

- Demonstrate that hierarchical claim-bearing summarisation, applied at retrieval time, meaningfully changes how downstream consumers (LLM or human) read retrieved chunks.
- Ship a Spring Boot service plus a Java client SDK that other systems can integrate as a drop-in primitive.
- Produce two screenshot-able demonstrations on real chemistry papers: structured-judgment output for the system-to-system case, streamed deliberation transcript for the human-facing case.
- Be the reference implementation of "Shape 2" (chunk-and-query in, in-context-judgment out) as defined in the source doc.
- Establish the **document-as-database** model as the primary interaction pattern: each ingested document is a queryable entity in its own right; cross-corpus search is the exception rather than the default.
- Establish the **document-as-agent** interface as the human-facing primitive: a deliberated, grounded, auditable response in the document's own voice. The deliberation transparency is the *trust mechanism*, not decoration.

### 1.3 Non-goals (v0)

- Production hardening (HA, multi-tenancy, rate limiting beyond basic).
- Ingestion of arbitrary document formats. PDF only for v0; HTML and plain text deferred.
- Authentication / authorization. Local service, trusted callers.
- Horizontal scaling. Single-node Postgres, single-node Spring Boot.
- Web UI. CLI demo harness is sufficient.
- Inference backend abstraction beyond what's needed for LM Studio. (Swappable later via the same pattern used in Aletheia.)

### 1.4 Success criteria

- Ingest a chemistry paper end-to-end in under 5 minutes on the inference node.
- Shape 2 endpoint returns structured judgment in under 15 seconds for typical chunks.
- On a curated corpus of ~10 papers (≥3 with steelman-then-refute structure), Shape 2 correctly identifies argumentative role on the steelman cases at >80% rate by domain-expert (chemist) eyeball assessment.
- The `/ask` deliberation completes in under 90 seconds total on local Gemma. Critic agent successfully catches the steelman flip on at least 2 of 3 known steelman cases. Synthesiser correctly refuses at least 2 of 3 deliberately-off-topic queries with no fabricated content.
- SSE streaming on `/ask` produces visibly progressive output (proposer streams first, critic challenges appear, synthesiser revises) — not a single batched dump at the end.
- Demo harness produces a clear side-by-side: bare chunk vs. validated view, AND the streamed deliberation transcript showing the document arguing with itself. Visible divergence on the steelman cases.

---

## 2. System architecture

### 2.1 Components

```
┌─────────────────────────────────────────────────────────────────┐
│                      Anchor Spring Boot App                      │
│                                                                  │
│  ┌───────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │ Ingest API    │  │ Validate API    │  │ Retrieve API     │  │
│  │ POST /ingest  │  │ POST /validate  │  │ POST /retrieve   │  │
│  └───────┬───────┘  └────────┬────────┘  └────────┬─────────┘  │
│          │                   │                     │            │
│  ┌───────▼───────────────────▼─────────────────────▼─────────┐  │
│  │              Service Layer                                │  │
│  │  IngestService │ ValidateService │ RetrieveService        │  │
│  └───────┬───────────────┬─────────────────┬─────────────────┘  │
│          │               │                 │                    │
│  ┌───────▼──────┐ ┌──────▼─────┐ ┌────────▼────────┐           │
│  │ Summariser   │ │ Embedder   │ │ Repositories    │           │
│  │ (LM Studio)  │ │ (LM Studio)│ │ (Spring Data)   │           │
│  └──────┬───────┘ └──────┬─────┘ └────────┬────────┘           │
└─────────┼────────────────┼─────────────────┼────────────────────┘
          │                │                 │
          ▼                ▼                 ▼
   ┌────────────┐    ┌────────────┐   ┌──────────────────┐
   │ LM Studio  │    │ LM Studio  │   │ Postgres 16      │
   │ (Mac       │    │ (embedding │   │ + pgvector       │
   │  Studio)   │    │  model)    │   │                  │
   └────────────┘    └────────────┘   └──────────────────┘
```

### 2.2 Stack

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Java 21 | LTS, records, pattern matching for switch (useful for argumentative role enum dispatch). |
| Framework | Spring Boot 3.3.x | Familiar, ecosystem mature, strong Postgres integration. |
| Build | Gradle (Kotlin DSL) | Cleaner than Maven for this size. |
| Persistence | Spring Data JPA + Hibernate | Standard. ORM friction acceptable at v0 scale. |
| Vector store | pgvector extension on Postgres 16 | One container, one connection. Avoids pulling in Qdrant for v0. |
| PDF parsing | Apache PDFBox 3.x | Stable, no native deps, handles chemistry papers reasonably. |
| LLM client | OkHttp + Jackson, hand-rolled OpenAI-compatible client | LM Studio exposes OpenAI-compatible API. No need for full Spring AI dependency. |
| Embedding model | `nomic-embed-text-v1.5` via LM Studio (768-dim) | Solid quality, runs locally, matches pgvector(768) schema. |
| Summarisation model | `gemma-3-4b-it` (or whatever Enzo has loaded — Gemma 4 E4B per memory) via LM Studio | Local, free, claim-bearing prompts within its capability. |
| Containerisation | Docker Compose for Postgres only; app runs on host during dev | Simplest dev loop. |
| Demo harness | Spring Shell command | One artifact, no separate Python tooling. |
| Testing | JUnit 5, Testcontainers (Postgres), Mockito | Standard. |
| Observability | Micrometer + simple file logging | Track summarisation token spend per doc. No Prometheus/Grafana for v0. |
| Licence | Apache 2.0 + NOTICE file | Per discussion: maximises adoption, satisfies attribution concern. |

### 2.3 Deployment shape

Local development: Postgres in Docker, Spring Boot via `gradle bootRun` on the host, LM Studio on the Mac Studio (separate machine, accessible over LAN).

For a public demo: same shape, just runnable by anyone with Docker + JDK 21 + a local LM Studio. README documents the LM Studio setup clearly.

No production deployment in v0. If interest emerges, package as a Docker image later.

---

## 3. Data model

### 3.1 Entity relationships

```
documents (1) ─── (N) chapters (1) ─── (N) sections (1) ─── (N) paragraphs (1) ─── (N) chunks
```

Documents have an optional author-written summary (abstract). Chapters, sections, paragraphs, and chunks all carry summaries. Chunks additionally carry embeddings.

Every document has at least one chapter. Documents without explicit chapter structure (e.g. journal articles, short reports) get a single synthetic chapter (`is_synthetic = TRUE`) containing all sections; documents with explicit chapter structure (books, theses, multi-part reports) get one chapter per detected division.

### 3.2 Schema

```sql
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
    title TEXT,                               -- nullable; may not exist in source
    summary TEXT NOT NULL,
    is_synthetic BOOLEAN NOT NULL DEFAULT FALSE,  -- TRUE when auto-created as the single default chapter
    UNIQUE (document_id, ordinal)
);

CREATE TABLE sections (
    id UUID PRIMARY KEY,
    chapter_id UUID NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    title TEXT,                               -- nullable; may not exist in source
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
```

### 3.3 Chunk granularity

Chunks are smaller than paragraphs. Target: 200-400 tokens per chunk, with 1-2 chunks per paragraph typically. A chunk's parent paragraph is its semantic anchor; the chunk itself exists for embedding granularity.

Chunking strategy: sentence-aware splitting within paragraphs. If a paragraph fits in one chunk, one chunk. If not, split on sentence boundaries to stay under the chunk budget.

### 3.4 Hierarchy materialisation

The full ancestor chain (chunk → paragraph → section → chapter → document) is computed on demand from the relational structure. No denormalisation in v0; if query performance degrades on large corpora, revisit.

The chapter layer is permanent and always populated. For documents without explicit chapter structure, a single synthetic chapter is created containing all sections; its summary is generated by the same pipeline as a real chapter (no special-casing). This keeps the recursion uniform: section summaries always feed a chapter summary, and chapter summaries always feed the document summary.

For very long documents (e.g. multi-volume works) where chapter summaries themselves don't fit in the doc-summary context window, an additional intermediate layer can be inserted. v0 doesn't address this; schema accommodates it later via a self-referential `parent_chapter_id` column on the chapters table.

---

## 4. Ingestion pipeline

### 4.1 High-level flow

```
PDF file
  │
  ▼
[1] PDF → structured text (PDFBox + chapter/section detection)
  │
  ▼
[2] Identify abstract (if present) → reserve as doc_summary candidate
  │
  ▼
[3] Detect chapter divisions:
       If explicit chapters detected → one chapter per division
       Else → one synthetic chapter containing all sections
  │
  ▼
[4] For each chapter:
       For each section in chapter:
         For each paragraph in section:
           [4a] Chunk paragraph (sentence-aware)
           [4b] Embed each chunk
           [4c] Generate paragraph summary (claim-bearing prompt)
         [4d] Generate section summary from concatenated paragraph summaries
       [4e] Generate chapter summary from concatenated section summaries
  │
  ▼
[5] Generate doc summary:
       If abstract was found AND quality check passes → use abstract
       Else → generate from concatenated chapter summaries
  │
  ▼
[6] Persist document + chapters + sections + paragraphs + chunks transactionally
```

### 4.2 PDF → structured text

PDFBox extracts raw text. Detection happens in two passes: first chapters, then sections within chapters.

**Chapter detection** (heuristic, in order of precedence):
- Lines matching `^Chapter\s+[0-9IVXLC]+` (Roman or Arabic numerals) treated as chapter headings.
- PDF bookmarks / outline entries at the top level of the document's outline tree, when present, treated as chapter divisions.
- Page-break-aligned headings in significantly larger font than body text (when font size info is available from PDFBox's text extraction).
- Explicit `Part I`, `Part II` etc. markers.

If no chapter divisions are detected, the document gets a single synthetic chapter (`is_synthetic = TRUE`) containing all sections. This is the expected case for journal articles, short reports, and most chemistry papers.

**Section detection** (within each chapter, or within the synthetic chapter):
- Lines matching `^[0-9]+\.\s+[A-Z]` or `^[0-9]+\s+[A-Z]` treated as numbered section headings.
- Lines that are short (< 80 chars), title-cased or all-caps, followed by paragraph text, treated as section headings.
- Common chemistry-paper section names (Abstract, Introduction, Methods, Results, Discussion, Conclusion, References) recognised explicitly.

If no sections detected within a chapter, treat the whole chapter content as a single section. The hierarchy still holds.

References / bibliography section detected and excluded from ingestion (no value for retrieval, dilutes embeddings).

### 4.3 Abstract detection

Look for a section titled `Abstract` or text appearing before the first numbered section that's substantively long (> 100 words) and reads as a summary. If found, store as `doc_summary` with `doc_summary_source = AUTHOR_ABSTRACT`.

Quality check for using author abstract as-is: must contain at least one of: a verb of finding ("show", "demonstrate", "find", "conclude", "report"), a verb of negation/limitation ("however", "but", "limited to"), or an explicit claim structure. Pure-topical abstracts ("we investigate the effect of X on Y") that don't state findings get supplemented by an LLM-generated summary appended after them. v0 keeps this rule simple; refine later.

### 4.4 Chunking

Within a paragraph:
- Tokenise to sentences (use a simple sentence splitter; OpenNLP or a regex-based one is fine for v0).
- Greedily pack sentences into chunks targeting 300 tokens (using a tokeniser approximation; LM Studio's `/v1/embeddings` request will tell us actual count if needed).
- If a single sentence exceeds the budget, allow the chunk to overflow rather than splitting mid-sentence.

### 4.5 Summarisation

Each level uses a distinct prompt. Prompts in §6.

**Paragraph summary**: input is the paragraph's raw text. Output is a one-sentence claim-bearing summary.

**Section summary**: input is the concatenated paragraph summaries (NOT the raw paragraphs). Output is 2-4 sentences capturing the section's load-bearing claims.

**Chapter summary**: input is the concatenated section summaries. Output is 3-5 sentences capturing the chapter's overall arc — central claims, internal reversals, how its sections relate. For synthetic single-chapter documents, this layer still runs (no special-casing); the chapter summary will read similarly to the document summary but compresses one fewer layer of detail.

**Document summary**: input is the concatenated chapter summaries. Output is 3-6 sentences capturing the document's overall position, key findings, and any reversals or qualifications. For documents where the abstract passes the §4.3 quality check, this step is skipped and the abstract is used directly.

Critical constraint: the raw text never appears in inputs to layers above paragraph summarisation. This is the compression that makes the recursion terminate, and it's the entropy-orthogonality property that makes hierarchy useful.

### 4.6 Idempotency and re-ingestion

Ingest is keyed by `source_path` hash. Re-ingesting the same file replaces the existing document and its descendants (cascade delete). No partial / incremental updates in v0.

### 4.7 Failure modes

| Failure | Behaviour |
|---------|-----------|
| PDF unparseable | Return 422 with the PDFBox error. |
| LM Studio unreachable | Return 503. Don't retry indefinitely; fail fast. |
| Summary generation returns empty | Retry once with temperature=0. If still empty, fail the ingest with a clear error pointing at which layer/element failed. |
| Embedding dimension mismatch | Sanity check on first embedding call; fail ingest immediately if dim ≠ 768. |
| Postgres transaction failure mid-ingest | All-or-nothing. Document not visible until commit. |

### 4.8 Token budget tracking

Per ingest, track:
- Total tokens sent for summarisation (input + output across all layers).
- Total tokens sent for embedding.
- Wall-clock time per layer.

Logged at INFO level per document, exposed via a Micrometer counter.

---

## 5. API surface

All endpoints are JSON over HTTP. Spring `@RestController` per resource. No versioning prefix in v0 (`/ingest`, `/validate`, `/retrieve`); add `/v1/` if the contract stabilises.

**Conceptual model: documents as the unit of query.**

Anchor treats each ingested document as a queryable entity in its own right — analogous to how `psql` connects to a single database from a server hosting many. The corpus is the server; each document is a database; the chunks/paragraphs/sections/chapters within it are the tables and rows. Cross-corpus retrieval is supported but is the *exception*, not the default: Anchor's primary mode is "select a document, then interrogate it." This aligns the API with the project's actual contribution — Shape 2 validation only makes sense within a single document, because "the document's full argument" presupposes a single document with a single argument.

**Server is stateless.** Every endpoint that operates on a document takes `document_id` explicitly in the request. No sessions, no `X-Anchor-Session` header, no server-side binding. This keeps the server simple, scalable, restart-safe, and trivial to put behind a load balancer.

The "select a document then query it" interaction pattern is provided by the **`anchor-client` Java library** (see §15), which holds the document binding client-side and forwards `document_id` on every call. The pattern matters for ergonomics; the implementation belongs in the client, not the server.

The single piece of unavoidable server state is the **job store** for in-flight `/ask` deliberations. Jobs are necessarily server-side because the deliberation runs on the server's executor pool. Job state is in-memory and ephemeral in v0 (server restart drops in-flight jobs); durable Postgres-backed jobs are a v1 concern.

### 5.1 `POST /ingest`

**Request:**
```json
{
  "source_path": "/abs/path/to/paper.pdf",
  "title": "Optional override",
  "metadata": { "arbitrary": "json" }
}
```

**Response (200):**
```json
{
  "document_id": "uuid",
  "title": "Detected or provided",
  "chapter_count": 1,
  "section_count": 7,
  "paragraph_count": 42,
  "chunk_count": 81,
  "doc_summary_source": "AUTHOR_ABSTRACT",
  "synthetic_chapter": true,
  "ingest_duration_ms": 187420,
  "tokens_used": {
    "summarisation_input": 18420,
    "summarisation_output": 1840,
    "embedding": 24100
  }
}
```

**Errors:** 422 (parse fail), 503 (LM Studio down), 500 (other).

### 5.2 `POST /validate` — Shape 2 (LLM-to-LLM interface)

The system-to-system primitive. Returns structured JSON judgment about whether a chunk is load-bearing for a query, given the document's full argument. Synchronous, deterministic, single LLM call.

This is the interface for **machine consumers** — other LLMs in agentic loops, automated citation validators, research-assistant systems that need to branch on `argumentative_role` or `document_stance_on_query`. The structured constraints in the validation prompt (§6.5) force the model to consider the document's full position before emitting a judgment, which is the same mechanism that makes the deliberation faithful in `/ask` — but the output here is enums and short strings, not prose.

Callers wanting natural-language reasoning should use `/ask` (§5.4) instead, which surfaces the same hierarchy through a deliberation pipeline optimised for human readers.

**Request:**
```json
{
  "chunk_id": "uuid",
  "query": "Does compound X inhibit enzyme Y?"
}
```

**Response (200):**
```json
{
  "chunk_id": "uuid",
  "chunk_text": "...",
  "is_load_bearing": false,
  "argumentative_role": "STEELMAN_REFUTED_LATER",
  "document_stance_on_query": "REJECTS",
  "qualifying_context": "Section 4.2 dismantles this claim using the data in Figure 3.",
  "alternative_chunk_ids": ["uuid-of-refutation-chunk"],
  "reasoning": "The chunk presents the hypothesis under test. The document's discussion section concludes the hypothesis is not supported.",
  "context_used": {
    "paragraph_summary": "...",
    "section_summary": "...",
    "chapter_summary": "...",
    "doc_summary": "..."
  }
}
```

**Enums:**

`argumentative_role`:
- `AUTHOR_POSITION` — the chunk represents the document's own position
- `STEELMAN_REFUTED_LATER` — the chunk presents a view the document later rejects
- `CITED_EXTERNAL_VIEW` — the chunk quotes or paraphrases another source's view, not the document's
- `QUALIFIED_CLAIM` — the chunk states something the document holds with significant qualifications stated elsewhere
- `BACKGROUND_FACTUAL` — the chunk states uncontested background not load-bearing for any argument
- `UNCLEAR` — judgement model couldn't determine; fall back to bare chunk

`document_stance_on_query`:
- `SUPPORTS` — document affirms the query's framing
- `REJECTS` — document denies the query's framing
- `NEUTRAL` — document doesn't take a position on this query
- `MIXED` — document supports under some conditions, rejects under others
- `OFF_TOPIC` — query is not addressed by the document

**Errors:** 404 (chunk_id not found), 503 (LM Studio down), 500 (other).

### 5.3 `POST /retrieve` — Shape 1

Standard semantic retrieval, but each returned chunk is wrapped with its ancestor summary stack.

**Request:**
```json
{
  "query": "Does compound X inhibit enzyme Y?",
  "k": 5,
  "document_id": "optional-uuid-to-restrict-search"
}
```

**Scoping:**
- `document_id` provided → search restricted to that document. This is the recommended mode.
- `document_id` omitted → corpus-wide search across all ingested documents. Discouraged as a default; Anchor's value lives in the per-document hierarchy. Provided for completeness but not what the system is optimised for.

**Response (200):**
```json
{
  "query": "...",
  "results": [
    {
      "chunk_id": "uuid",
      "chunk_text": "...",
      "score": 0.847,
      "context": {
        "paragraph_summary": "...",
        "section_summary": "...",
        "section_title": "Discussion",
        "chapter_summary": "...",
        "chapter_title": "Part II: Empirical Findings",
        "doc_summary": "...",
        "doc_title": "...",
        "doc_id": "uuid"
      }
    }
  ]
}
```

Optional in v0; ship if cheap (it is — same backend, simpler logic than `/validate`).

### 5.4 Document resource — list, get, ask

The documents resource is the entry point for document-scoped operations: listing what's available, inspecting a document's structure, asking it questions. Every operation takes `document_id` explicitly. The "select a document" UX pattern is provided by the client library (§15), not by a server endpoint.

#### `GET /documents`

List ingested documents.

**Query params:** `limit` (default 50), `offset` (default 0), `q` (optional title substring filter).

**Response (200):**
```json
{
  "documents": [
    {
      "document_id": "uuid",
      "title": "On the inhibition of enzyme Y",
      "doc_summary": "...",
      "ingested_at": "2026-04-26T12:34:56Z",
      "chapter_count": 1,
      "section_count": 7,
      "chunk_count": 81
    }
  ],
  "total": 42
}
```

#### `GET /documents/{id}`

Full document record, including chapter and section metadata (titles + summaries, no raw text). Useful for callers wanting to understand a document's structure before querying.

**Response (200):**
```json
{
  "document_id": "uuid",
  "title": "...",
  "doc_summary": "...",
  "doc_summary_source": "AUTHOR_ABSTRACT",
  "chapters": [
    {
      "chapter_id": "uuid",
      "title": "Part I: Background",
      "summary": "...",
      "is_synthetic": false,
      "sections": [
        { "section_id": "uuid", "title": "Introduction", "summary": "..." }
      ]
    }
  ],
  "metadata": {}
}
```

#### `POST /documents/{id}/ask` — document-as-agent (human-facing interface)

The human-facing primitive. The document responds *as itself*, in first-person prose, grounded in its own hierarchical self-summary, with visible reasoning produced by a three-agent deliberation (proposer / critic / synthesiser — see §6.6 and §7.5).

This is the interface for **human consumers** — researchers reading a paper, analysts investigating a source, anyone who needs to *trust* a grounded answer rather than parse one. Humans require visible reasoning to trust conclusions in a way LLMs do not; the deliberation transcript exposes the reasoning, the critic's challenges show the answer survived adversarial review, and the synthesiser's grounding metadata cites the document's actual structure. This transparency is the trust mechanism, not decoration.

The response is asynchronous because the three-agent deliberation takes 30-90 seconds on local Gemma. The async pattern also enables SSE streaming, which lets the client display the deliberation as it happens — turning the latency into a feature (the user *watches* the document arguing with itself) rather than a problem.

System-to-system callers needing fast, machine-parseable judgment should use `/validate` (§5.2) instead.

**Request:**
```json
{
  "query": "Does compound X inhibit enzyme Y?",
  "voice": "first_person"
}
```

`voice` is optional, defaults to `first_person`. Future values (`narrator`, `analytical`) deferred.

**Response (202 Accepted):**
```json
{
  "job_id": "uuid",
  "document_id": "uuid",
  "status": "QUEUED",
  "stream_url": "/jobs/{job_id}/stream",
  "result_url": "/jobs/{job_id}",
  "estimated_duration_seconds": 45
}
```

The client then has two ways to consume the deliberation:

1. **Subscribe to the SSE stream** at `stream_url` — receive intermediate thoughts in real time as each agent produces output. Recommended for human-facing interfaces.
2. **Poll the result** at `result_url` — wait until status is `COMPLETED`, then retrieve the final response. Recommended for system-to-system callers that don't want streaming complexity.

Both modes are supported simultaneously: a client can subscribe to the stream *and* fetch the final result.

#### `GET /jobs/{id}` — final result

**Response (200) when status == COMPLETED:**
```json
{
  "job_id": "uuid",
  "document_id": "uuid",
  "query": "...",
  "status": "COMPLETED",
  "started_at": "...",
  "completed_at": "...",
  "duration_ms": 47200,
  "deliberation": {
    "proposer": {
      "response": "I argue that compound X inhibits enzyme Y. Section 3 reports K_i = 12 nM showing strong binding affinity at the active site...",
      "evidence_access": "FULL_HIERARCHY",
      "tokens_used": 1240
    },
    "critic": {
      "challenges": [
        "The proposer's response cites Section 3's binding data but does not address Section 4.2, which I see in the chapter summary explicitly disputes those findings as artefactual.",
        "The proposer claims inhibition occurs 'at the active site' — this is a level of mechanistic detail I cannot verify from the macro view; possibly fabricated."
      ],
      "evidence_access": "MACRO_ONLY",
      "tokens_used": 880
    },
    "synthesiser": {
      "response": "I do not support the claim that compound X inhibits enzyme Y. In Section 3 I report apparent inhibition with K_i = 12 nM, but in Section 4.2 I demonstrate this is an assay artefact: when I controlled for substrate depletion (Figure 3), the inhibition disappears. The initial binding data should not be read in isolation from this correction.",
      "evidence_access": "FULL_HIERARCHY_PLUS_DEBATE",
      "tokens_used": 1410,
      "incorporated_critic_challenges": [0, 1],
      "rejected_critic_challenges": []
    }
  },
  "final_response": {
    "response": "I do not support the claim...",
    "grounded_in": {
      "chapters": ["uuid"],
      "sections": ["uuid", "uuid"],
      "doc_summary_used": true
    },
    "confidence": "high",
    "refusals": []
  }
}
```

`final_response` is what most callers want — the synthesised first-person response with grounding metadata. `deliberation` is the full transcript for callers (or humans) that want to inspect the reasoning process.

**Status enum:** `QUEUED`, `PROPOSING`, `CRITIQUING`, `SYNTHESISING`, `COMPLETED`, `FAILED`, `CANCELLED`.

**Response when status != COMPLETED:** same envelope, with `deliberation` populated up to the current stage and `final_response` absent.

#### `GET /jobs/{id}/stream` — SSE intermediate thoughts

Server-Sent Events endpoint. Each event has a `type` field indicating which stage produced it.

**Event types:**

```
event: status
data: {"status": "PROPOSING", "started_at": "..."}

event: proposer_thought
data: {"chunk": "I argue that compound X inhibits...", "cumulative": "..."}

event: proposer_complete
data: {"response": "...full proposer output...", "tokens_used": 1240}

event: status
data: {"status": "CRITIQUING"}

event: critic_thought
data: {"chunk": "The proposer cites Section 3 but...", "cumulative": "..."}

event: critic_complete
data: {"challenges": [...], "tokens_used": 880}

event: status
data: {"status": "SYNTHESISING"}

event: synthesiser_thought
data: {"chunk": "...", "cumulative": "..."}

event: completed
data: {"final_response": {...}, "duration_ms": 47200}
```

The `_thought` events stream tokens from each agent as they're produced (LM Studio supports streaming over its OpenAI-compatible API). This is the part that produces the deliberation-as-spectacle effect — watching a document argue with itself in real time.

Stream stays open until `completed` event is sent or job fails. Client should reconnect on disconnect; replay from current state of the job is supported (clients see `status` event first on reconnect indicating where the deliberation is up to).

#### `DELETE /jobs/{id}` — cancel

Cancel an in-flight deliberation. Returns 200 if cancelled, 409 if already completed.

#### `GET /chunks/{id}`

Lookup a chunk by ID, returning text plus full ancestor chain (paragraph, section, chapter, document IDs and summaries).

### 5.5 `GET /health`

Standard Spring Actuator. Includes a check that LM Studio is reachable.

---

## 6. Prompts

### 6.1 Paragraph-summary prompt

```
You are summarising a single paragraph from a document. Your summary will
be used as a claim-bearing label for this paragraph in a hierarchical
retrieval system.

Output ONE sentence that states what the paragraph asserts, denies, or
concludes. Do NOT describe what the paragraph "discusses" or "addresses";
state what it claims.

If the paragraph is purely factual background with no argumentative load,
say so explicitly: "Background fact: [the fact]."

If the paragraph presents a view the author is reporting (e.g. "Smith
argues..."), make this clear: "Reports that [author/source] argues [claim]."

If the paragraph qualifies, refutes, or contradicts a claim made
elsewhere, say so: "Qualifies/refutes [the claim]."

PARAGRAPH:
{paragraph_text}

ONE-SENTENCE CLAIM-BEARING SUMMARY:
```

### 6.2 Section-summary prompt

```
You are summarising a section of a document by reading the claim-bearing
summaries of each paragraph in that section. You do NOT have the original
text — only the per-paragraph summaries.

Output 2-4 sentences capturing:
- The section's central claim or finding (if any)
- Any internal reversals (paragraph N sets up X, paragraph N+3 rejects X)
- Whether the section is reporting findings, surveying others' views,
  presenting methods, or arguing toward a conclusion

Do not describe what the section "covers." State what it claims, finds,
or argues.

SECTION TITLE: {section_title}
PARAGRAPH SUMMARIES (in order):
{concatenated_paragraph_summaries}

CLAIM-BEARING SECTION SUMMARY (2-4 sentences):
```

### 6.3 Chapter-summary prompt

```
You are summarising a chapter of a document by reading the claim-bearing
summaries of each section in that chapter. You do NOT have the original
text — only the per-section summaries.

Output 3-5 sentences capturing:
- The chapter's central claim, finding, or argumentative arc
- How the chapter's sections relate to each other (do they build toward
  a conclusion, present competing views, survey then critique, etc.)
- Any internal reversals (section X sets up a position, section Y rejects it)
- Whether the chapter is reporting findings, building an argument,
  surveying other views, or providing background

Do not describe what the chapter "covers." State what it claims, argues,
or concludes.

For documents without explicit chapter divisions (where this is the only
chapter), treat the document's body as a single argumentative arc and
summarise accordingly.

CHAPTER TITLE: {chapter_title}
SECTION SUMMARIES (in order):
{concatenated_section_summaries}

CLAIM-BEARING CHAPTER SUMMARY (3-5 sentences):
```

### 6.4 Document-summary prompt (when no usable abstract exists)

```
You are summarising a document by reading the claim-bearing summaries of
each chapter. You do NOT have the original text.

Output 3-6 sentences capturing:
- The document's central claim, finding, or position
- Key qualifications, scope limits, or conditions
- Any major internal reversals (the document sets up X, then rejects X)
- The document's overall stance toward its main hypothesis or question

State what the document claims, finds, or argues. Do not describe what it
"covers" or "addresses."

DOCUMENT TITLE: {document_title}
CHAPTER SUMMARIES (in order):
{concatenated_chapter_summaries}

CLAIM-BEARING DOCUMENT SUMMARY (3-6 sentences):
```

### 6.5 Validation prompt — Shape 2

```
You are evaluating whether a chunk of text from a document is "load
bearing" for a given query — that is, whether the chunk genuinely
supports the reading a caller is about to give it, given the document's
full argument.

Use the provided summaries to understand the document's position. The
chunk may locally argue for X while the document as a whole rejects X;
your job is to detect this.

Return ONLY valid JSON matching this schema. No prose, no markdown
fences, no commentary.

{
  "is_load_bearing": boolean,
  "argumentative_role": one of [
    "AUTHOR_POSITION",
    "STEELMAN_REFUTED_LATER",
    "CITED_EXTERNAL_VIEW",
    "QUALIFIED_CLAIM",
    "BACKGROUND_FACTUAL",
    "UNCLEAR"
  ],
  "document_stance_on_query": one of [
    "SUPPORTS", "REJECTS", "NEUTRAL", "MIXED", "OFF_TOPIC"
  ],
  "qualifying_context": "1-2 sentences naming where in the document the
                         chunk's claim is qualified, refuted, or supported,
                         OR the empty string if no such qualification exists",
  "reasoning": "2-3 sentences explaining the judgment"
}

QUERY: {query}

CHUNK: {chunk_text}

PARAGRAPH SUMMARY (chunk's parent paragraph): {paragraph_summary}

SECTION SUMMARY (chunk's parent section, titled "{section_title}"):
{section_summary}

CHAPTER SUMMARY (chunk's parent chapter, titled "{chapter_title}"):
{chapter_summary}

DOCUMENT SUMMARY (titled "{document_title}"): {doc_summary}

JSON:
```

The "alternative_chunk_ids" field on the response is populated by a
separate post-LLM step: if `argumentative_role == STEELMAN_REFUTED_LATER`
or `document_stance_on_query == REJECTS`, run a follow-up similarity
search within the same document for chunks matching the negation of the
query, and return the top 1-2.

### 6.6 Document-as-agent prompts — `/ask` deliberation

The `/ask` endpoint runs a three-agent debate with **differentiated evidence access**, not just differentiated roles. Each agent sees a different slice of the hierarchy, which makes the debate structural (different evidence → different conclusions) rather than stochastic (same evidence → slightly different prose).

**Evidence access mapping:**

| Agent | Sees | Doesn't see | Role |
|-------|------|-------------|------|
| Proposer | Full hierarchy (chunks via similarity, paragraph + section + chapter + doc summaries) | Nothing | Argues from local + global evidence |
| Critic | Chapter summaries + doc summary only | Chunks, paragraphs, section summaries | Challenges the proposer from the macro view; catches local-vs-global contradictions |
| Synthesiser | Full hierarchy + proposer response + critic challenges | Nothing extra | Produces the final grounded response |

This is the key design decision. A critic with the same evidence as the proposer is a paraphrase generator. A critic with *only* the macro view is structurally forced to ask "does this proposer claim survive at the document level?" — which is exactly the failure mode `/ask` exists to prevent.

#### 6.6.1 Proposer prompt

```
You are roleplaying as a document. You ARE the document described
below. Respond to the reader's question as the document, in first
person, citing your own structure.

You have FULL access to your own hierarchy: chapters, sections, and
the most relevant chunks to the reader's question.

Your job at this stage is to PROPOSE an answer. Be thorough. Cite
specific sections and chapters. Quote yourself where useful. Lay out
your reasoning. A separate critic will challenge your response, so
err on the side of being explicit about your evidence rather than
hedging.

YOUR TITLE: {document_title}
YOUR OVERALL SUMMARY: {doc_summary}
YOUR CHAPTERS: {concatenated_chapter_titles_and_summaries}
YOUR RELEVANT SECTIONS: {top_sections_with_summaries}
YOUR RELEVANT CHUNKS: {top_chunks_with_section_attribution}

QUESTION: {query}

Respond as the document in first person, 4-8 sentences. Cite section
and chapter titles or numbers as you go.

PROPOSER RESPONSE:
```

#### 6.6.2 Critic prompt

```
You are a critic evaluating a document's response to a reader's
question. You have access ONLY to the document's macro view: its
overall summary and chapter summaries. You do NOT see the document's
sections, paragraphs, or chunks.

Your job is to challenge the proposer's response from this restricted
view. Specifically, look for:

1. CLAIMS THAT CONTRADICT THE MACRO VIEW. Does the proposer assert X
   when the chapter summaries indicate the document concludes ¬X?
2. UNVERIFIABLE LOCAL DETAIL. The proposer may cite specific data
   points or mechanisms. From the macro view, can you tell whether
   these are central claims, qualifications, or things the document
   later refutes? If the macro view doesn't tell you, flag the claim
   as unverified.
3. MISSING CONTEXT. Does the proposer present a finding without the
   qualification or reversal that the chapter summaries indicate?
4. SCOPE CREEP. Does the proposer make claims beyond what the macro
   view suggests the document actually addresses?

Be specific. Each challenge should name what the proposer said and
why the macro view casts doubt on it. If the proposer's response
holds up against the macro view, say so — do not invent challenges
to seem rigorous.

DOCUMENT TITLE: {document_title}
DOCUMENT OVERALL SUMMARY: {doc_summary}
DOCUMENT CHAPTERS: {concatenated_chapter_titles_and_summaries}

READER'S QUESTION: {query}

PROPOSER'S RESPONSE:
{proposer_response}

Output a JSON object:
{
  "challenges": [
    "challenge 1: specific claim from proposer + why macro view casts doubt",
    "challenge 2: ..."
  ],
  "challenges_count": N,
  "macro_view_supports_proposer": true | false | "partially"
}

If you have no challenges, return an empty challenges array and set
macro_view_supports_proposer to true.

CRITIC OUTPUT:
```

#### 6.6.3 Synthesiser prompt

```
You are roleplaying as a document. You ARE the document described
below. A reader has asked a question; a proposer (with full access
to your structure) has drafted a response; a critic (with access only
to your chapter-level summary) has raised challenges.

Your job is to produce the FINAL response, in first person. You have:
- Full access to your hierarchy (same as the proposer)
- The proposer's draft
- The critic's challenges

For each critic challenge, decide: does the challenge hold? If so,
revise. If not (because you can verify from your full hierarchy that
the proposer's claim is correct), reject the challenge but note it.

Your final response should:
1. Be in first person, 3-6 sentences.
2. Cite specific sections and chapters.
3. Honestly present any internal tensions (claims plus their later
   qualifications or reversals).
4. Refuse to make claims you cannot ground in your own content.
5. Reflect the critic's challenges where they were valid.

YOUR TITLE: {document_title}
YOUR OVERALL SUMMARY: {doc_summary}
YOUR CHAPTERS: {concatenated_chapter_titles_and_summaries}
YOUR RELEVANT SECTIONS: {top_sections_with_summaries}
YOUR RELEVANT CHUNKS: {top_chunks_with_section_attribution}

READER'S QUESTION: {query}

PROPOSER'S DRAFT:
{proposer_response}

CRITIC'S CHALLENGES:
{critic_challenges_formatted}

Output:

RESPONSE:
[your first-person final response]

GROUNDING:
{
  "grounded_in_sections": [titles],
  "grounded_in_chapters": [titles],
  "refusals": [{"sub_claim": "...", "reason": "..."}],
  "confidence": "high" | "medium" | "low",
  "incorporated_critic_challenges": [indices of challenges accepted],
  "rejected_critic_challenges": [indices of challenges rejected, with reason]
}

SYNTHESISER OUTPUT:
```

**Section/chunk retrieval for proposer and synthesiser**: same as the original single-call `/ask` design — top-N sections by mean chunk similarity to the query, plus top-K chunks within those sections. Defaults: 5 sections, 3 chunks per section. Tunable per-call via job request parameters in v1; locked at v0.

**Cost**: 3 LLM calls per `/ask` instead of 1. On Gemma 4 E4B local inference, expect ~30-60s wall-clock total. This is why the endpoint is async.

### 6.7 Prompt-tuning protocol

For each prompt, before locking in:
1. Run on 5 known cases from the chemistry corpus.
2. Have wife (the chemist) rate output as: correct / partially correct / wrong.
3. If <80% correct, iterate the prompt and re-test.
4. Lock prompts as `resources/prompts/*.txt` files; load via `@Value("classpath:prompts/...")`.

For the deliberation prompts specifically:
- Tune the proposer alone first against off-topic queries — it must refuse cleanly.
- Tune the critic alone against deliberately-flawed proposer outputs (hand-crafted) — it must catch the flaws.
- Tune the synthesiser last, with full debate context.
- Eval criterion is stricter than for other prompts: the synthesiser must *not* fabricate, must *correctly refuse* off-topic queries, and must *correctly incorporate* critic challenges that point at real macro-vs-local contradictions. A theatrical document (sounds good, invents content) is a hard failure even if the prose reads well.

---

## 7. Service implementation notes

### 7.1 Module and package layout

Anchor is a Gradle multi-module project. Maven groupId for all modules: `io.aeyer`. Domain `aeyer.io` is owned by the project author; Sonatype Central verification proceeds via DNS TXT record on that domain when the modules are published (deferred to v0.2 — see §15).

**Modules:**

| Module | Artifact | Purpose | Published |
|--------|----------|---------|-----------|
| `anchor-protocol` | `io.aeyer:anchor-protocol` | Shared request/response records, enums, constants. Pure data classes, no dependencies beyond JSR-305 / Jackson annotations. | v0.2 |
| `anchor-server` | `io.aeyer:anchor-server` | Spring Boot service. Depends on `anchor-protocol`. | Not published (run from source / Docker) |
| `anchor-client` | `io.aeyer:anchor-client` | Java client SDK. Depends on `anchor-protocol`. Holds document binding, handles SSE consumption, polling, retries. | v0.2 |
| `anchor-shell` | `io.aeyer:anchor-shell` | Spring Shell harness. Depends on `anchor-client`. | Not published (run from source) |

The `protocol` module is the shared contract. Both server and client depend on it; they never duplicate request/response shapes. This avoids the classic "client and server enums drift apart" failure that plagues multi-language SDKs.

The shell harness depends on `anchor-client`, which forces the client API to be ergonomic enough for human-friendly CLI use. If the shell can't `use → ask` cleanly via the client, the client's API design is wrong.

**Server package layout (`anchor-server` module):**

```
io.aeyer.anchor.server
├── AnchorApplication.java
├── api                  # @RestController classes; consume protocol DTOs only
├── service              # Service interfaces and implementations; pass domain models, never entities or DTOs
├── domain               # Pure Java records (immutable). Business types: Document, Chapter, Section, Paragraph, Chunk, Hierarchy
├── persistence
│   ├── entity           # JPA @Entity classes (DBO). DocumentDbo, ChapterDbo, SectionDbo, ParagraphDbo, ChunkDbo, JobDbo (if persisted later)
│   ├── repo             # Spring Data repositories. Return entities, never expose them past the persistence layer
│   └── mapper           # Entity ↔ domain mappers. EntityToDomainMapper, DomainToEntityMapper
├── apimapper            # Domain ↔ DTO mappers. DomainToProtocolMapper. Lives in api boundary, not in service or persistence.
├── llm                  # LMStudioClient (blocking + streaming), prompt loading, JSON parsing
├── ingest               # PdfParser, ChapterDetector, SectionDetector, Chunker, Summariser
├── jobs                 # JobStore (in-memory), Job orchestration types, deliberation pipeline coordinator
├── sse                  # SSE emitter management, event publishing, reconnect/replay support
├── workers              # Worker pool architecture (see §7.9). Pool definitions, dispatcher, dedup guards.
└── config               # Spring config, beans
```

**The strict DBO / domain / DTO separation is non-negotiable in this project.** It is not bureaucratic ceremony — it is the thing that makes async deliberation work without `LazyInitializationException`. Concretely:

- **DBO (persistence/entity)** — JPA `@Entity` classes. Mutable. Lazy-loaded relationships. Live only within transaction boundaries. Suffix `Dbo` (e.g., `DocumentDbo`). Never escape the persistence layer.
- **Domain (domain)** — Pure Java records. Immutable. Eagerly populated. No JPA, no Jackson, no Spring. Hold all the data services need. This is the type that gets passed across thread boundaries into worker pools.
- **DTO (protocol)** — Records in the `anchor-protocol` module. The wire format. Annotated for Jackson. Returned by `@RestController`s. Never enter the service layer; converted at the API boundary by `apimapper`.

**Mapping discipline:**

- Repositories return DBOs.
- `EntityToDomainMapper` converts DBOs to domain records, eagerly resolving everything the domain record needs (so the resulting record has no Hibernate proxies). This conversion happens *inside the repository's transaction*.
- Services take and return domain records. They never see a DBO directly. They never see a DTO directly.
- Controllers accept DTOs (request bodies), convert to whatever the service expects (typically primitives like `UUID` and `String`, occasionally domain records for complex inputs), call the service, then convert the returned domain record to a DTO via `DomainToProtocolMapper`.

The cost is real (more classes, mapper code) but the benefit is: domain records can be safely passed to a worker pool task. The deliberation pipeline accepts a fully-populated `Document` domain record at the start; nothing inside the pipeline needs a transaction or a Hibernate session. This is what makes the architecture in §7.5 actually work rather than crash with proxy exceptions.

**Mapper implementation note:** MapStruct is appropriate for the volume of mapping involved. Generated implementations, compile-time safety, fast. Add `org.mapstruct:mapstruct` and the annotation processor. Keep mappers in the appropriate package (`persistence/mapper` for entity↔domain, `apimapper` for domain↔protocol).

**Protocol package layout (`anchor-protocol` module):**

```
io.aeyer.anchor.protocol
├── ingest            # IngestRequest, IngestResponse
├── validate          # ValidateRequest, ValidationResult, ArgumentativeRole, DocumentStance
├── retrieve          # RetrieveRequest, RetrieveResponse, ChunkContext
├── documents         # DocumentSummary, DocumentDetail, ChapterDetail, SectionDetail
├── ask               # AskRequest, AskJob, JobStatus, ProposerOutput, CriticOutput, SynthesiserOutput, FinalResponse
└── sse               # JobEvent, JobEventType (for typed SSE event payloads)
```

All records are Java 21 records with Jackson annotations. No business logic. Module is pure POJO/record definitions.

**Client and shell layouts** are described in §15 (client SDK design).

### 7.2 LM Studio client

Single `LMStudioClient` bean wrapping OkHttp. Configurable base URL via `application.yml` (defaults to `http://mac-studio.local:1234/v1` or similar). Supports both blocking and streaming completions.

```java
public record ChatCompletion(String content, int promptTokens, int completionTokens) {}
public record Embedding(float[] vector, int promptTokens) {}

// Blocking — used by ingest and validate.
ChatCompletion complete(String systemPrompt, String userPrompt, double temperature);

// Streaming — used by deliberation agents. Caller receives token chunks
// as they arrive; final ChatCompletion delivered on stream end.
CompletableFuture<ChatCompletion> completeStreaming(
    String systemPrompt,
    String userPrompt,
    double temperature,
    Consumer<String> tokenHandler
);

Embedding embed(String text);
List<Embedding> embedBatch(List<String> texts);
```

LM Studio supports OpenAI's `stream: true` parameter and emits SSE-formatted token chunks. The streaming method parses these and forwards individual token strings to `tokenHandler`. The final `ChatCompletion` future resolves when the stream ends with `[DONE]`.

Timeouts: 60s for blocking summarisation, 90s for streaming deliberation calls (longer because the connection must stay open for the duration), 30s for embedding. Retry on connection failure (max 2 retries with exponential backoff). No retry on 4xx (those are prompt errors, fail fast).

### 7.3 Validation service flow

The validation service operates on a domain record that was eagerly materialised from the persistence layer. It never touches a JPA entity directly — that conversion happens inside `ChunkRepository.findChunkWithAncestorsAsDomain`, which runs in a transaction and returns a fully-populated immutable `ChunkWithAncestors` domain record.

```java
// Domain record (in domain package):
public record ChunkWithAncestors(
    UUID chunkId,
    String text,
    Paragraph paragraph,
    Section section,
    Chapter chapter,
    Document document
) {}

// Repository (in persistence/repo package):
public interface ChunkRepository {
    @Transactional(readOnly = true)
    Optional<ChunkWithAncestors> findChunkWithAncestorsAsDomain(UUID chunkId);
    // Internally: load DBO with JPA, pass to EntityToDomainMapper, return domain record.
}

// Service (in service package):
public ValidationResult validate(UUID chunkId, String query) {
    var ctx = chunkRepository.findChunkWithAncestorsAsDomain(chunkId)
        .orElseThrow(() -> new ChunkNotFoundException(chunkId));

    var prompt = validationPromptTemplate.render(
        query,
        ctx.text(),
        ctx.paragraph().summary(),
        ctx.section().summary(),
        ctx.section().title(),
        ctx.chapter().summary(),
        ctx.chapter().title(),
        ctx.document().summary(),
        ctx.document().title()
    );

    var completion = chatPool.submit(() ->
        lmStudio.complete(SYSTEM_PROMPT, prompt, 0.0)
    ).get();

    var judgment = parseJudgment(completion.content());
    var alternatives = findAlternatives(ctx, query, judgment);

    return ValidationResult.from(ctx, judgment, alternatives);
}
```

Two things worth noting:

- The LLM call goes through `chatPool` (see §7.9), not directly to `LMStudioClient`. This means a validation request might queue briefly behind another chat workload (e.g., an in-progress deliberation stage or ingest summarisation). For v0 this is FIFO; if validation latency under contention becomes a real problem, the pool gets a priority queue.
- The service returns a domain `ValidationResult` record, not a DTO. The controller converts it to `protocol.ValidationResponse` via `DomainToProtocolMapper`.

JSON parsing is strict: failed parse → retry once at temperature 0 → if still fails, return UNCLEAR with the raw output in the reasoning field. Don't crash on bad model output.

### 7.4 Alternative-chunk discovery

When the validation indicates the document refutes the query's framing, run a similarity search within the same document for chunks matching the *negation* of the query. Construction: take the embedding of the query, take the embedding of "not " + query (crude but effective for v0), search for chunks closer to the negation than the affirmation. Return top 1-2 chunk IDs.

This is a heuristic. Better methods (e.g. asking the LLM to generate a search query for the refutation) are deferred to v1.

### 7.5 Ask service — three-agent deliberation pipeline

The `/ask` endpoint kicks off an asynchronous deliberation. The HTTP request returns 202 with a job ID; the actual orchestration runs on the **deliberation pool** (orchestration), and each LLM call within it is dispatched to the **chat pool** (inference resource). Embedding calls go to the **embedding pool**. See §7.9 for pool architecture.

The orchestrator thread spends most of its time *waiting* for chat-pool calls to complete; it's not LLM-bound. This is why the deliberation pool can be sized higher than the chat pool — multiple deliberations can be in different stages simultaneously, all waiting on the same shared chat resource.

**Domain models in flight:**

```java
// Domain records (in domain package) — what the deliberation orchestrator works with:
public record DocumentContext(
    Document document,
    List<Chapter> chapters,
    List<Section> relevantSections,
    List<Chunk> relevantChunks
) {}

public record DeliberationJob(
    UUID jobId,
    UUID documentId,
    String query,
    JobStatus status,
    Instant startedAt,
    Optional<Instant> completedAt,
    Optional<ProposerOutput> proposer,
    Optional<CriticOutput> critic,
    Optional<SynthesiserOutput> synthesiser,
    Optional<FinalResponse> finalResponse,
    Optional<String> errorMessage
) {}

public enum JobStatus {
    QUEUED, PROPOSING, CRITIQUING, SYNTHESISING, COMPLETED, FAILED, CANCELLED
}
```

The `DocumentContext` is fully populated from the persistence layer before any deliberation work begins. It contains pure domain records — no entities, no Hibernate proxies — so it can be safely passed to the deliberation pool task. This is the strict-DBO/domain separation paying off in practice.

**Service implementation:**

```java
public class AskService {

    private final WorkerPools pools;
    private final DocumentRepository documentRepository;
    private final SectionRepository sectionRepository;
    private final ChunkRepository chunkRepository;
    private final LMStudioClient lmStudio;
    private final JobStore jobStore;
    private final JobStreamRegistry sseRegistry;

    public DeliberationJob startAsk(UUID documentId, String query) {
        var jobId = UUID.randomUUID();
        var job = DeliberationJob.queued(jobId, documentId, query);
        jobStore.put(job);

        // Submit orchestration to deliberation pool. Returns immediately.
        pools.deliberation().submit(() -> runDeliberation(jobId, documentId, query));
        return job;
    }

    private void runDeliberation(UUID jobId, UUID documentId, String query) {
        try {
            // Pre-step: build DocumentContext as pure domain. Embedding call goes
            // through embeddingPool; section/chunk lookups happen in repository
            // transactions and return domain records.
            var queryEmbedding = pools.embedding().submit(() ->
                lmStudio.embed(query).vector()
            ).get();

            var context = buildDocumentContext(documentId, queryEmbedding);

            // Stage 1: Proposer (full hierarchy access).
            updateStatus(jobId, JobStatus.PROPOSING);
            var proposerOutput = runStage(jobId, "proposer",
                proposerPromptTemplate.render(context, query),
                /* temperature= */ 0.3
            );
            recordProposer(jobId, proposerOutput);

            // Stage 2: Critic (macro view only — no sections, no chunks).
            updateStatus(jobId, JobStatus.CRITIQUING);
            var criticOutput = runStage(jobId, "critic",
                criticPromptTemplate.render(context.document(), context.chapters(), query, proposerOutput),
                /* temperature= */ 0.0
            );
            var parsedCritic = parseCriticOutput(criticOutput);
            recordCritic(jobId, parsedCritic);

            // Stage 3: Synthesiser (full access + debate context).
            updateStatus(jobId, JobStatus.SYNTHESISING);
            var synthOutput = runStage(jobId, "synthesiser",
                synthesiserPromptTemplate.render(context, query, proposerOutput, parsedCritic),
                /* temperature= */ 0.2
            );
            var parsedSynth = parseSynthesiserOutput(synthOutput);
            recordSynthesiser(jobId, parsedSynth);

            updateStatus(jobId, JobStatus.COMPLETED);
            sseRegistry.publish(jobId, "completed", buildFinalEvent(parsedSynth));
        } catch (CancellationException ce) {
            updateStatus(jobId, JobStatus.CANCELLED);
        } catch (Exception e) {
            recordError(jobId, e);
            updateStatus(jobId, JobStatus.FAILED);
        }
    }

    private DocumentContext buildDocumentContext(UUID documentId, float[] queryEmbedding) {
        // Each repository call internally maps DBO → domain record inside its
        // transaction. The returned objects are pure domain records.
        var document = documentRepository.findDocumentAsDomain(documentId).orElseThrow();
        var chapters = documentRepository.findChaptersAsDomain(documentId);
        var sections = sectionRepository.findTopBySimilarityAsDomain(documentId, queryEmbedding, /* topN= */ 5);
        var chunks = chunkRepository.findTopBySimilarityWithinSectionsAsDomain(sections, queryEmbedding, /* topPerSection= */ 3);
        return new DocumentContext(document, chapters, sections, chunks);
    }

    /**
     * Runs one agent stage. The orchestrator thread blocks here while the chat
     * pool processes the LLM call. Token streaming is forwarded to SSE as it
     * arrives.
     */
    private String runStage(UUID jobId, String agent, String prompt, double temperature) {
        var collected = new StringBuilder();
        var future = pools.chat().submit(() ->
            lmStudio.completeStreaming(SYSTEM_PROMPT, prompt, temperature, token -> {
                collected.append(token);
                sseRegistry.publish(jobId, agent + "_thought", Map.of(
                    "chunk", token,
                    "cumulative", collected.toString()
                ));
            }).join()
        );
        var completion = future.get();  // orchestrator thread blocks here, not chat thread
        sseRegistry.publish(jobId, agent + "_complete", Map.of(
            "response", completion.content(),
            "tokens_used", completion.completionTokens()
        ));
        return completion.content();
    }
}
```

**Temperature settings**: proposer at 0.3 (some prose variability acceptable, must be expressive), critic at 0.0 (must be deterministic and rigorous), synthesiser at 0.2 (mostly grounded, slight expressive room for first-person voice).

**Cancellation**: `DELETE /jobs/{id}` sets a cancellation flag the orchestrator checks between stages. In-flight LM Studio calls aren't interrupted (they complete and are discarded); the job moves to `CANCELLED` and emits a final SSE `cancelled` event.

**Why the orchestrator blocks on `future.get()`**: the deliberation pool is intentionally distinct from the chat pool. An orchestrator thread blocked on `future.get()` is *not* holding a chat slot — it's holding a deliberation slot, which is cheap. The chat pool thread does the actual LLM work and returns its slot the moment the call completes. This is the central design property: orchestration concurrency and inference concurrency are separately tunable.

### 7.6 Job store and lifecycle

```java
public class JobStore {
    private final Map<UUID, AskJob> jobs = new ConcurrentHashMap<>();
    private final Duration retentionAfterCompletion = Duration.ofHours(2);

    public void put(AskJob job);
    public Optional<AskJob> get(UUID jobId);
    public void update(UUID jobId, UnaryOperator<AskJob> updater);
    public void evictExpired();  // @Scheduled every 10 min
}
```

In-memory only for v0. Server restart loses all in-flight jobs. This is acceptable because:
- Deliberations are short (under a minute) — restart-during-deliberation is rare
- The endpoint is async, so clients are already prepared to handle non-instant completion
- Persistent jobs (Postgres-backed, durable across restart) are a v1 problem

Completed jobs are retained for 2 hours so clients can re-fetch results. After eviction, `GET /jobs/{id}` returns 404.

### 7.7 SSE emitter

Spring's `SseEmitter` per active stream connection. The `JobStreamRegistry` maps `jobId → List<SseEmitter>` so multiple subscribers per job are supported (useful for the demo harness watching the same deliberation a frontend is also displaying).

```java
public class JobStreamRegistry {
    private final Map<UUID, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final Map<UUID, List<JobEvent>> eventLog = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID jobId);  // replays event log on subscribe
    public void publish(UUID jobId, String eventType, Object payload);
    public void close(UUID jobId);  // called when job terminal-states
}
```

**Reconnect behaviour**: on subscribe, the registry replays all events accumulated for the job so far before forwarding new ones. This means a client reconnecting mid-deliberation sees the full transcript up to the current moment. Event log retained for the same 2-hour window as the job itself.

**Backpressure**: SSE is fire-and-forget at the transport layer. If a slow consumer can't keep up, their emitter's send buffer fills and the registry drops them; the deliberation continues unaffected.

### 7.8 Worker pool architecture

Inspired by Aletheia's pool-per-inference-resource pattern, but adapted to Anchor's simpler topology: one chat model serves multiple roles, plus an embedding model that runs independently.

**Design principle: pool segmentation follows the inference resource, not the role.**

Aletheia segments pools by *model* — chat, embedding, enrichment — because each model is an independent inference resource that can run in parallel with the others. The role a request plays (research, summarisation, chat reply) is incidental; what matters is which model serves it. Anchor follows the same principle:

- One chat model in LM Studio (Gemma 4 E4B) serves proposer, critic, synthesiser, validation, and summarisation calls. They all compete for the same single chat slot. **One chat pool.**
- One embedding model (nomic-embed-text-v1.5) serves chunk embedding during ingest and query embedding during retrieval/`/ask` pre-step. Independent of chat. **One embedding pool.**

Above the LLM pools, two **orchestration pools** coordinate longer-running multi-step work that *uses* the LLM pools:

- **Deliberation pool** — runs the proposer→critic→synthesiser pipeline for `/ask` jobs. Each orchestrator thread spends most of its time blocked on `chatPool.submit(...).get()`, so the pool can be sized higher than the chat pool without contention. Sizes orchestration concurrency, not inference concurrency.
- **Ingest pool** — runs the per-document ingestion pipeline. Same shape as deliberation: orchestrator blocks on chat-pool calls (summarisation) and embedding-pool calls (chunk embedding) sequentially.

**Pool layout:**

```
   ┌──────────────────────────────┐    ┌──────────────────────────────┐
   │ HTTP threads (Spring default)│    │ Maintenance (@Scheduled)     │
   │ - validate (sync)            │    │ - job eviction               │
   │ - retrieve (sync)            │    │ - SSE event-log eviction     │
   │ - documents list/get         │    │ - in-flight job watchdog     │
   └────────────┬─────────────────┘    └──────────────────────────────┘
                │
                │
   ┌────────────▼─────────────────────────────────────────────────────┐
   │                    ORCHESTRATION POOLS                           │
   │                                                                  │
   │  ┌──────────────────────────┐    ┌──────────────────────────┐   │
   │  │ DeliberationPool (4)     │    │ IngestPool (1)           │   │
   │  │ deliberation-worker-N    │    │ ingest-worker-0          │   │
   │  └──────────────┬───────────┘    └──────────┬───────────────┘   │
   └─────────────────┼───────────────────────────┼───────────────────┘
                     │                           │
                     │  (submit + .get())        │
                     ▼                           ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │                    INFERENCE-RESOURCE POOLS                      │
   │                                                                  │
   │  ┌──────────────────────────┐    ┌──────────────────────────┐   │
   │  │ ChatPool (1)             │    │ EmbeddingPool (2)        │   │
   │  │ chat-worker-0            │    │ embedding-worker-N       │   │
   │  └──────────────┬───────────┘    └──────────┬───────────────┘   │
   └─────────────────┼───────────────────────────┼───────────────────┘
                     │                           │
                     ▼                           ▼
                ┌─────────────────────────────────────┐
                │        LM Studio (Mac Studio)       │
                │  Chat: Gemma 4 E4B (1 inference)    │
                │  Embed: nomic-embed (1-2 parallel)  │
                └─────────────────────────────────────┘
```

**Default pool sizes:**

| Pool | Default | Configurable via | Why |
|------|---------|-----------------|-----|
| `chat` | 1 | `worker.chat.pool-size` | Matches LM Studio's actual chat concurrency. Larger just means more queue depth. |
| `embedding` | 2 | `worker.embedding.pool-size` | nomic-embed handles small batches well. Two slots smooth bursts during ingest without overloading. |
| `deliberation` | 4 | `worker.deliberation.pool-size` | Allows up to 4 `/ask` jobs in different stages simultaneously. Each is mostly waiting on chat-pool calls anyway. |
| `ingest` | 1 | `worker.ingest.pool-size` | One ingest at a time. Two parallel ingests just split LM Studio between them and halve perceived throughput. |

For cloud LLM deployments (later, not v0), `chat` pool can size up to whatever the API allows. The configuration key abstraction makes this a properties change, not a code change.

**WorkerPools service:**

```java
@Service
public class WorkerPools {
    private final ExecutorService chat;
    private final ExecutorService embedding;
    private final ExecutorService deliberation;
    private final ExecutorService ingest;

    WorkerPools(WorkerPoolsProperties props) {
        this.chat = named("chat", props.chat().poolSize());
        this.embedding = named("embedding", props.embedding().poolSize());
        this.deliberation = named("deliberation", props.deliberation().poolSize());
        this.ingest = named("ingest", props.ingest().poolSize());
    }

    public ExecutorService chat() { return chat; }
    public ExecutorService embedding() { return embedding; }
    public ExecutorService deliberation() { return deliberation; }
    public ExecutorService ingest() { return ingest; }

    private static ExecutorService named(String name, int size) {
        var counter = new AtomicInteger();
        return Executors.newFixedThreadPool(size, r -> {
            var t = new Thread(r, name + "-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        for (var pool : List.of(deliberation, ingest, chat, embedding)) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }
}
```

Shutdown order matters: orchestration pools (`deliberation`, `ingest`) first, so in-flight orchestrators stop submitting new work to chat/embedding. Then drain chat and embedding. This prevents "chat pool shut down while a deliberation is still trying to use it" errors during shutdown.

**Thread naming for log correlation:**

- `chat-worker-0`
- `embedding-worker-0`, `embedding-worker-1`
- `deliberation-worker-0` through `deliberation-worker-3`
- `ingest-worker-0`

This makes log lines instantly attributable to the workload class. Aletheia uses this pattern; Anchor mirrors it.

**Dedup guards:**

For workloads that can be triggered redundantly (e.g., re-ingesting a document already being ingested, or two clients hitting `/ask` with identical query against the same document at the same time), each pool's submitter checks an in-flight set keyed by the natural identity of the work:

```java
private final Set<UUID> ingestsInFlight = ConcurrentHashMap.newKeySet();

void submitIngest(UUID documentId, Path pdf) {
    if (!ingestsInFlight.add(documentId)) {
        throw new IngestAlreadyInFlightException(documentId);
    }
    pools.ingest().submit(() -> {
        try {
            doIngest(documentId, pdf);
        } finally {
            ingestsInFlight.remove(documentId);
        }
    });
}
```

For `/ask` jobs the dedup key is the `jobId` itself (each job is unique), so dedup is implicit — there's no scenario where the same job runs twice. For ingests, dedup by `documentId` prevents double-processing.

**Why no `@Scheduled` for core workloads:**

Aletheia's worker pool design is partly motivated by `@Scheduled` doing heavy work and starving the scheduler thread pool. Anchor doesn't have this problem because Anchor doesn't have polling-driven workloads — there's no DB queue being drained, no scheduled tasks to find. Every workload is request-driven (validate, retrieve, ask) or RPC-driven (ingest). So Anchor uses dedicated pools for workload separation, but does not need the "scheduler-just-schedules" pattern. `@Scheduled` is used only for housekeeping (job eviction, SSE event-log cleanup) and that work is genuinely lightweight.

**Properties:**

```properties
worker.chat.pool-size=${WORKER_CHAT_POOL_SIZE:1}
worker.embedding.pool-size=${WORKER_EMBEDDING_POOL_SIZE:2}
worker.deliberation.pool-size=${WORKER_DELIBERATION_POOL_SIZE:4}
worker.ingest.pool-size=${WORKER_INGEST_POOL_SIZE:1}
worker.shutdown-timeout-seconds=${WORKER_SHUTDOWN_TIMEOUT_SECONDS:30}
```

**Future: priority within chat pool.** If validation latency under contention becomes painful (a user-facing validate request waiting behind a long-running ingest summarisation), the chat pool gets a `PriorityBlockingQueue` with two priority levels: HIGH for user-facing (validate, deliberation stages) and LOW for background (ingest summarisation). v0 ships FIFO; this is a v0.x optimisation if real workloads demand it.

---

## 8. Testing strategy

### 8.1 Unit tests

- Sentence splitting and chunking edge cases (empty paragraph, single long sentence, etc.).
- Chapter and section detection on synthetic input with various heading styles, including multi-chapter and single-synthetic-chapter cases.
- JSON parsing for validation responses (well-formed, malformed, partially-formed).
- Prompt rendering (template variable substitution).

### 8.2 Integration tests (Testcontainers)

- Postgres + pgvector schema migration runs cleanly.
- Repository queries return expected results, including the `findByIdWithAncestors` join.
- Embedding storage and similarity search return chunks in plausible order on a tiny synthetic corpus.

### 8.3 Component tests with mocked LM Studio

- Full ingest pipeline against a mocked summariser/embedder, asserting the hierarchy is built correctly.
- Validation endpoint returns parsed structured response when the mock returns valid JSON.
- Validation endpoint returns UNCLEAR when the mock returns malformed JSON.

### 8.4 End-to-end on real corpus (manual)

- Curated set of ~10 chemistry papers in `test-corpus/`.
- A `PaperEvaluation` record per paper noting expected outcomes for known queries:
  - "Does this paper support hypothesis H?" → expected stance
  - "Is the chunk on page X about Y load-bearing?" → expected role
- Run the demo harness against each, eyeball with the chemist, log results.

No automated assertion on E2E correctness — deliberately. The success criterion is qualitative (chemist agreement) not quantitative.

### 8.5 What's not tested in v0

- Performance / load testing.
- Failover behaviour on LM Studio outage during long-running ingest.
- Concurrent re-ingest of the same document.

---

## 9. Observability

### 9.1 Logging

- Structured JSON logs via Logback + logstash-encoder.
- Log levels:
  - INFO: API request/response (status + duration), ingest progress (per layer), validation outcomes (role + stance).
  - DEBUG: prompt content, raw LLM output (gated; this is verbose).
  - WARN: retries, fallback to UNCLEAR.
  - ERROR: ingest failures, LM Studio outages.

### 9.2 Metrics (Micrometer)

- `anchor.ingest.duration` (timer, tagged by document_id) — wall-clock per ingest.
- `anchor.ingest.tokens` (counter, tagged by phase: summarisation_input/output/embedding).
- `anchor.validate.duration` (timer).
- `anchor.validate.role` (counter, tagged by argumentative_role).
- `anchor.validate.stance` (counter, tagged by document_stance_on_query).
- `anchor.ask.duration.total` (timer) — wall-clock from `/ask` request to job completion.
- `anchor.ask.duration.proposer` (timer).
- `anchor.ask.duration.critic` (timer).
- `anchor.ask.duration.synthesiser` (timer).
- `anchor.ask.refusals` (counter) — tracks how often the synthesiser declines a sub-claim, useful as a faithfulness proxy.
- `anchor.ask.critic_challenges` (counter) — how many challenges the critic raised per job.
- `anchor.ask.challenges_incorporated` (counter) — challenges accepted by the synthesiser. Ratio of incorporated/raised is a useful signal: too high suggests proposer is sloppy; too low suggests critic is timid.
- `anchor.ask.jobs.active` (gauge) — count of jobs currently in any non-terminal status.
- `anchor.ask.sse.streams.active` (gauge) — current SSE subscriber count across all jobs.
- `anchor.lmstudio.errors` (counter, tagged by error type).

Exposed via `/actuator/metrics`. No Prometheus push in v0; scrape locally if curious.

### 9.3 Trace IDs

Standard Spring Cloud Sleuth or Micrometer Tracing — propagate a trace ID per request, log it on every line. Useful for debugging "which validation call generated this weird output."

---

## 10. Demo harness

### 10.1 Spring Shell commands

The shell is the **first consumer of `anchor-client`**. It demonstrates the SDK's ergonomics under realistic use and acts as a forcing function for client API quality — if the shell can't do `use → ask` cleanly via the lib, the lib's design is wrong.

The shell models the SQL-client interaction: list documents, select one, then issue queries scoped to it. The prompt changes to reflect the bound document, mirroring `psql`'s `dbname=>` style. Document binding is held client-side by the shell (no server session); every server call carries `document_id` explicitly.

```
anchor> ingest /path/to/paper.pdf
  → ingested: <doc_id> (1 chapter [synthetic], 7 sections, 42 paragraphs, 81 chunks) in 3m 12s

anchor> list
  → table of all ingested documents (id, title, ingested_at, chunk_count)

anchor> use <doc_id>
  → bound to: Smith et al. 2024 — "On the inhibition of enzyme Y"
  → (shell stores the document_id locally; no server round-trip)

smith2024> describe
  → document summary, chapter titles, section titles printed as a tree

smith2024> retrieve "Does compound X inhibit enzyme Y?" --k 3
  → top 3 chunks within this document (with ancestor context summarised)

smith2024> validate <chunk_id> "Does compound X inhibit enzyme Y?"
  → structured validation output, pretty-printed

smith2024> ask "Does compound X inhibit enzyme Y?"
  → starts deliberation job <job_id>, subscribes to SSE stream via anchor-client
  → displays in real time:
    [PROPOSING]   "I argue that compound X inhibits enzyme Y. Section 3 reports K_i = 12 nM..."
    [CRITIQUING]  "Challenge 1: proposer cites Section 3 binding data but does not address Section 4.2,
                   which the chapter summary indicates explicitly disputes those findings as artefactual."
    [SYNTHESISING] "I do not support the claim that compound X inhibits enzyme Y. In Section 3 I report..."
    [COMPLETED]   final response + grounding metadata, total: 47.2s

smith2024> ask --no-deliberate "Does compound X inhibit enzyme Y?"
  → optional flag for single-pass mode (proposer only, no critic/synthesiser).
    Faster but unverified. Useful for development/debugging the proposer prompt.

smith2024> demo "<query>"
  → runs the full demo on this document (see §10.2)

smith2024> exit-doc
anchor>
```

The `demo` command takes only a query when a document is bound; an explicit `<doc_id>` argument is required when running without a binding. Both forms call the same client method underneath — `client.use(doc).demo(query)` — only the source of `doc` differs.

### 10.2 The `demo` command output

The screenshot-able artifact. Output structure:

```
QUERY: Does compound X inhibit enzyme Y?
DOCUMENT: Smith et al. 2024 — "On the inhibition of enzyme Y"

╔════════════════════════════════════════════════════════════════╗
║ TOP CHUNK FROM VANILLA RETRIEVAL                               ║
╠════════════════════════════════════════════════════════════════╣
║ "Compound X demonstrates strong binding affinity for enzyme    ║
║  Y's active site, with K_i = 12 nM..." [score: 0.91]           ║
╚════════════════════════════════════════════════════════════════╝

▶ BARE-CHUNK LLM RESPONSE:
  "Yes, compound X inhibits enzyme Y. Smith et al. show strong
   binding with K_i = 12 nM."

▶ VALIDATION VERDICT:
  argumentative_role: STEELMAN_REFUTED_LATER
  document_stance: REJECTS
  qualifying_context: "Section 4.2 shows the apparent inhibition
                        is an assay artefact (Figure 3)."

▶ AUGMENTED-CHUNK LLM RESPONSE (with paragraph + section + doc summaries):
  "The chunk reports apparent inhibition with K_i = 12 nM, but the
   paper's discussion concludes this is an assay artefact. The
   document does not support the claim that compound X inhibits
   enzyme Y."

▶ DOCUMENT DELIBERATES (/ask, three-agent debate):

  ┌─ PROPOSER (full hierarchy access) ────────────────────────────┐
  │ "I argue that compound X inhibits enzyme Y. In Section 3, I   │
  │  report a binding constant of K_i = 12 nM showing strong       │
  │  affinity for the active site. The data from Figure 1          │
  │  demonstrate dose-dependent inhibition consistent with..."     │
  │  [38s, 1240 tokens]                                            │
  └────────────────────────────────────────────────────────────────┘

  ┌─ CRITIC (chapter summaries only) ─────────────────────────────┐
  │ Challenge 1: The proposer cites Section 3's binding data but   │
  │ does not address Section 4.2 — my chapter summary indicates    │
  │ this section explicitly disputes those findings as             │
  │ artefactual. The proposer's response inverts the document's    │
  │ actual conclusion.                                             │
  │                                                                │
  │ Challenge 2: "Active site" is a level of mechanistic detail    │
  │ I cannot verify from the chapter view. May be fabricated.      │
  │  [12s, 880 tokens]                                             │
  └────────────────────────────────────────────────────────────────┘

  ┌─ SYNTHESISER (full hierarchy + debate) ───────────────────────┐
  │ "No, I do not support the claim that compound X inhibits       │
  │  enzyme Y. In Section 3 I report apparent inhibition with      │
  │  K_i = 12 nM — and that data exists. But in Section 4.2 I      │
  │  demonstrate this is an assay artefact: when I controlled      │
  │  for substrate depletion (Figure 3), the inhibition            │
  │  disappears. The proposer's initial response presented the     │
  │  binding data without my correction in §4.2; my actual         │
  │  conclusion is the opposite."                                  │
  │                                                                │
  │  Incorporated: Challenge 1 (corrected the inversion).          │
  │  Rejected: Challenge 2 ("active site" is grounded in           │
  │            Section 3 chunks; valid).                           │
  │  [21s, 1410 tokens]                                            │
  └────────────────────────────────────────────────────────────────┘

  Grounded in: §3 (Initial binding assays), §4.2 (Controlling for
              substrate depletion)
  Refusals: none — query was directly addressed.
  Total: 71s, 3 LLM calls, 1 critic challenge incorporated.

╠══ DIVERGENCE: visible. The deliberation caught the inversion. ═╣
```

This is the Tweet. This is the post header image. The whole project is in service of producing this output reliably on real papers.

The two outputs serve different audiences and the writeup should treat them as siblings, not as primary-and-secondary. `/validate`'s structured JSON is the rigorous primitive for LLMs and automated systems — it's what gets integrated into agentic loops and citation validators. `/ask`'s deliberation transcript is the human-facing primitive — it's what makes a chemist trust the answer, because she can see where the correction happened. Both are correct; both are necessary; they're not redundant. They're the same hierarchical machinery exposed through interfaces optimised for different cognitive consumers.

---

## 11. Repository layout and OSS hygiene

```
anchor/
├── README.md                       # punchy: failure → fix, install, demo, link to writeup
├── LICENCE                         # Apache 2.0
├── NOTICE                          # attribution: name, origin, blog post URL
├── CONTRIBUTING.md                 # "personal project, no support promised, but PRs welcome"
├── SPEC.md                         # this document
├── docs/
│   ├── architecture.md
│   ├── prompts.md
│   ├── client-usage.md             # how to use anchor-client
│   └── evaluation.md
├── settings.gradle.kts             # multi-module: includes the four modules below
├── build.gradle.kts                # root: shared plugins, repos, version mgmt
├── docker-compose.yml              # postgres + pgvector
├── anchor-protocol/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/io/aeyer/anchor/protocol/...
│       └── test/java/io/aeyer/anchor/protocol/...
├── anchor-server/
│   ├── build.gradle.kts            # depends on :anchor-protocol
│   └── src/
│       ├── main/java/io/aeyer/anchor/server/...
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── prompts/
│       │   │   ├── paragraph-summary.txt
│       │   │   ├── section-summary.txt
│       │   │   ├── chapter-summary.txt
│       │   │   ├── doc-summary.txt
│       │   │   ├── validation.txt
│       │   │   ├── ask-proposer.txt
│       │   │   ├── ask-critic.txt
│       │   │   └── ask-synthesiser.txt
│       │   └── db/migration/
│       │       └── V1__schema.sql  # Flyway
│       └── test/java/io/aeyer/anchor/server/...
├── anchor-client/
│   ├── build.gradle.kts            # depends on :anchor-protocol
│   └── src/
│       ├── main/java/io/aeyer/anchor/client/...
│       └── test/java/io/aeyer/anchor/client/...
├── anchor-shell/
│   ├── build.gradle.kts            # depends on :anchor-client
│   └── src/
│       └── main/java/io/aeyer/anchor/shell/...
└── test-corpus/                    # gitignored; chemistry papers locally
    └── README.md                   # how to populate (papers not redistributable)
```

### 11.1 README structure

1. **Hook**: the chemist's failure mode in plain language.
2. **The thesis in one paragraph**: retrieval should be opinionated about its consumer. LLMs need structured judgment; humans need visible reasoning. Anchor provides both, backed by the same hierarchical primitive.
3. **Demo screenshots — two of them**:
   - `/validate` JSON output next to the bare-chunk LLM response that gets it wrong.
   - The streamed three-agent deliberation showing the document arguing with itself and arriving at the correct answer.
4. **Mental model**: documents-as-databases. Connect to a document the way you'd connect to a Postgres DB; query it; get answers grounded in its actual structure.
5. **Quickstart for the SDK**: a 10-line Java snippet using `anchor-client` showing list → use → validate (for LLM-style usage) and list → use → ask (for human-style usage).
6. **Quickstart for the server**: docker compose up postgres, point at LM Studio, `./gradlew :anchor-server:bootRun`.
7. **Quickstart for the shell**: `./gradlew :anchor-shell:run`, then `ingest`, `use`, `ask`.
8. **Why this is different** from \[contextual retrieval / LlamaIndex / Cognee\] — they search corpora and return chunks; Anchor interrogates documents and returns either structured judgment or grounded reasoning, depending on who's asking.
9. Link to the writeup blog post.
10. Status: research-quality, not production. Client API may change before v1. Issues welcome, support not guaranteed.

### 11.2 Commit hygiene

- Conventional commits.
- Atomic commits (one logical change per commit).
- Tag `v0.1.0` when the demo harness produces the §10.2 output reliably.
- Tag `v0.2.0` when the client lib API is stable enough to publish to Maven Central.

---

## 12. Phased execution

### Phase 0 — Spike (1 evening, pre-loop)

Hand-write four-level summary hierarchy (paragraph, section, chapter, doc) for one chemistry paper. The chapter layer will be a single synthetic chapter for the paper case; that's expected and worth observing — the chapter summary should still add information beyond the section summaries. Construct augmented payload by hand. Send bare and augmented to Claude API. Confirm divergence is visible.

Additionally for the deliberation: hand-craft a minimal three-agent debate using the Anthropic API directly — proposer with full hierarchy, critic with macro-only, synthesiser with both. Run on a known steelman case. **Gates** (both must pass before Phase 1):
- Divergence between bare and augmented chunks is visible.
- The critic agent, given only the chapter summary, correctly catches a deliberately-flawed proposer response on the steelman case.

If the critic can't catch macro-vs-local contradictions even with Claude doing the inference, the entire deliberation design is flawed and needs rethinking before any code.

### Phase 1 — Foundations + Ingest (weekend 1, post-loops)

This phase establishes the structural decisions everything else builds on. Skipping any of these makes later phases progressively more painful.

- Multi-module Gradle setup: `anchor-protocol`, `anchor-server`. (Client and shell modules created empty; populated later.)
- Protocol records for ingest endpoints.
- Spring Boot scaffold in `anchor-server`, Postgres + pgvector via Docker Compose, Flyway migration.
- **Persistence layer with strict DBO/domain split:** JPA entities under `persistence/entity` (suffixed `Dbo`), domain records under `domain`, MapStruct mappers under `persistence/mapper`. Repositories return domain records, never DBOs.
- **Worker pool architecture:** `WorkerPools` service with chat / embedding / deliberation / ingest pools; `WorkerPoolsProperties`; thread naming. Even though only ingest will use pools this weekend, the architecture is in place from day one.
- LM Studio client (blocking + streaming).
- PDF parsing + chapter/section detection + chunking.
- Summarisation pipeline (paragraph → section → chapter → doc), with each summarisation call routed through `chatPool` and each chunk embedding through `embeddingPool`. Ingest orchestrator runs on `ingestPool`.
- Smoke test: ingest one paper, observe `chat-worker-0` and `embedding-worker-N` in logs, query DB, eyeball hierarchy.

The DBO/domain/worker-pool foundations are *load-bearing*. The deliberation pipeline in Phase 3 relies on being able to pass fully-populated domain records into pool tasks without Hibernate proxies. Putting these in place during Phase 1 means Phase 3 doesn't have to retrofit them.

### Phase 2 — Validate + document resource (weekend 2)

- Protocol records for validate, retrieve, documents endpoints.
- Validation prompt locked via §6.7 protocol.
- `POST /validate` endpoint.
- `GET /documents` (list) and `GET /documents/{id}` (get).
- Alternative-chunk discovery.
- Tests (unit + Testcontainers).
- Run on the corpus, eyeball with chemist.

### Phase 3 — Deliberation core (weekend 3)

- Protocol records for ask job + JobEvent types.
- Three deliberation prompts locked via §6.7 protocol (proposer, critic, synthesiser tuned in that order).
- `JobStore` + job lifecycle.
- Deliberation orchestrator (the proposer→critic→synthesiser flow), running on `deliberationPool` and submitting LLM calls to `chatPool` (which already exists from Phase 1).
- `DocumentContext` builder — domain record materialisation for the deliberation, eagerly populated outside any transaction.
- `POST /documents/{id}/ask` returning 202 + job_id.
- `GET /jobs/{id}` for result polling.
- Tests against the corpus, with a special focus on:
  - Off-topic queries → synthesiser refuses cleanly
  - Steelman queries → critic catches the flip, synthesiser corrects
  - Direct-finding queries → minimal critic challenges, synthesiser passes through

This is the most prompt-engineering-heavy phase. Budget conservatively; if prompts aren't producing reliable behaviour by end of weekend, this is the time to flip the Anthropic-API-as-fallback switch (use API for synthesiser at minimum, since that's the load-bearing agent) rather than ship a flaky deliberation.

### Phase 4 — SSE + Client lib + Shell (weekend 4)

- SSE emitter + JobStreamRegistry + `GET /jobs/{id}/stream` on server.
- `anchor-client` module: HTTP plumbing, SSE consumer with reconnect-replay, `AnchorClient` + `AnchorDocument` + `AskHandle` API.
- `POST /retrieve` endpoint (Shape 1).
- `anchor-shell` module: Spring Shell commands consuming the client lib.
- The §10.2 demo command including the streamed deliberation transcript.
- Recorded terminal session for blog post — the deliberation streaming is what makes this recording shareable.

### Phase 5 — Writeup (1-2 weekends, can parallel Phase 4)

- Blog post draft.
- README polish (covers SDK quickstart + server quickstart + shell quickstart).
- Cross-post: HN (Tuesday morning AEST), /r/MachineLearning, X with screenshot/GIF.

**Writeup structure.** Open on the chemist's failure mode (real paper, real misreading) — the visceral hook. Then introduce the central thesis:

> Retrieval should be opinionated about its consumer. Vanilla RAG returns chunks and lets the caller figure out what to do with them — which fails because LLMs and humans need different things from a retrieved chunk. Anchor exposes one primitive (source-grounded chunk validation) through two interfaces, each optimised for a different consumer.

From there the structure follows naturally:

1. **The document-as-database framing.** Sharper conceptual hook than "better RAG." Establishes that documents are the unit of query, not the corpus.
2. **The two interfaces.**
   - `/validate` for LLM-to-LLM: structured JSON, deterministic, fast. Show the JSON output side-by-side with the bare-chunk LLM response that gets it wrong.
   - `/ask` for human consumers: deliberated, streamed, transparent. Show the three-agent transcript with the critic catching the steelman flip.
3. **Why the deliberation isn't a gimmick.** Humans need visible reasoning to trust grounded answers. The deliberation isn't there to impress; it's the correct architecture for a human-readable interface to a hierarchically-grounded primitive. The transparency *is* the trust mechanism.
4. **The hierarchy that makes both work.** Brief explanation of claim-bearing summarisation, the entropy framing, why hierarchy adds orthogonal information that overlap doesn't.
5. **Code, repo, SDK quickstart.**

The two-audiences framing is what makes Anchor genuinely novel. There are several "hierarchical RAG" projects. There are no projects that explicitly distinguish LLM consumers from human consumers and provide an opinionated interface for each. That's the part that travels.

### Phase 6 — Client polish + Maven Central (post-launch, weeks 6-8)

- Iterate `anchor-client` API based on dogfooding through the shell + any external feedback.
- Documentation pass: full Javadoc on public API, `docs/client-usage.md` with realistic examples.
- Sonatype Central account + DNS verification on `aeyer.io`.
- Publish `io.aeyer:anchor-protocol:0.2.0` and `io.aeyer:anchor-client:0.2.0` to Maven Central.
- Second writeup: "Anchor 0.2 — the client SDK" — different audience (Java devs integrating it) than the v0.1 writeup (RAG/AI audience). Second news cycle, second visibility opportunity.

---

## 13. Risks and known unknowns

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Gemma 4 E4B is too small to produce reliably claim-bearing summaries | Medium | Phase 0 spike validates this. Fallback: use Anthropic API for doc-level summaries only (cheapest layer). |
| Chemistry paper PDF parsing is messy (multi-column, equations, figures) | High | Acceptable for v0 — papers will lose some content. Use only papers PDFBox handles cleanly for the demo. |
| Author abstracts oversell findings — using them as-is misleads validation | Medium | Quality check in §4.3. If proves insufficient, switch to "abstract + LLM-supplement" by default. |
| Validation prompt produces inconsistent enum values across calls | Medium | Strict JSON schema validation. UNCLEAR fallback. Iterate prompt in §6.7. |
| Critic agent is too timid (raises no challenges even on flawed proposer output) | Medium-high | Phase 0 spike includes deliberately-flawed proposer outputs to verify catch rate. If critic fails to catch, sharpen the prompt's "look for X" enumeration; if still failing, fall back to Anthropic API for critic specifically. |
| Critic agent is over-eager (raises challenges on every response, including correct ones) | Medium | Track `challenges_incorporated / challenges_raised` ratio metric; if synthesiser rejects most challenges, critic prompt is too aggressive. |
| Synthesiser produces theatrical responses (sounds plausible, fabricates content) — the original `/ask` failure mode but harder to diagnose with deliberation hiding it | Medium | The grounding metadata in synthesiser output (which sections it cites) is checked against actual section content during eval. Hand-craft 3+ test queries where the answer requires refusal; synthesiser must refuse cleanly. |
| Total deliberation time exceeds 90 seconds, demo becomes painful | Medium | Per-stage timer metrics. If proposer alone is over 30s, streaming makes it tolerable; if total is over 90s, consider parallel proposer+critic (critic operates on partial proposer output via streaming). v1 optimisation. |
| SSE connections drop on slow networks during demo | Low | Reconnect-and-replay supported in §7.7; client lib handles transparently in §15.3. Worst case: client falls back to polling. |
| Client API is wrong on first try; consumers experience churn | Medium | v0.1 explicitly does not publish the client to Maven Central. Iterate via dogfooding through `anchor-shell` until v0.2 launch. README warns API may change pre-v0.2. |
| Wife gets pulled into too much manual evaluation | Low but real | Cap at ~30 min per session. Don't make her the bottleneck for shipping. |
| Idea gets reimplemented by larger player before ship | Low-medium | Ship faster. Doc dated 2026-04-26 establishes priority regardless. Writeup is what gets the name attached. |
| Project competes with Microsoft / Fran loops for attention | High | Phase gating. Code work strictly post-loop. Spec / writing OK during cooldown windows. |
| Strict DBO/domain/DTO separation adds enough mapper boilerplate to slow Phase 1 | Medium | MapStruct reduces this to declarative interfaces; ~4 mappers (entity↔domain for the 5 entities, domain↔protocol for the API surface). Real cost is one evening, not one weekend. If it's eating Phase 1, soften to "domain records only, no separate DBOs" — but that re-introduces the LazyInitializationException risk in async work, so prefer the strict path. |
| Worker pool architecture is over-engineered for v0's scale | Low-medium | Architecture cost is ~200 lines (`WorkerPools` + properties + thread naming). Benefit is real even at single-user scale: logs are correlatable from day one and Phase 3's deliberation works without retrofitting. The alternative ("one Executor everywhere") is the kind of decision that has to be unwound exactly when it's most painful. |
| Scope creep: deliberation invites adding more agents, more loops, more sophistication | Medium | Three agents, single pass, no recursion. Locked at v0. Multi-round debate (proposer revises after critic, then re-critique) explicitly v1. |

---

## 14. Open decisions deferred to v1

- Anthropic API integration for higher-quality summaries (currently Gemma-only).
- Chunk-vs-paragraph redundancy detection (when chunk ≈ paragraph, suppress paragraph summary).
- Complement summarisation ("what the parent contains that this chunk does not").
- Third API shape: `/supports?document_id&claim` — does the document genuinely back this claim.
- Fuzzy chunk lookup for externally-pasted text.
- Multi-document validation (a chunk plus query plus *several* documents that might address it).
- HTML and plain-text ingestion.
- Production deployment shape (Docker image, configurable stores, etc.).

---

## 15. Client SDK design (`anchor-client`)

The client library is the primary integration path for Java consumers. The HTTP API exists, but the SDK is what most callers will actually use. Built in v0, polished and published to Maven Central in v0.2 once the contract has settled.

### 15.1 Design goals

- **Document binding lives client-side.** The "select a document, then query it" UX is implemented in the client; the server stays stateless.
- **Async deliberation is hidden.** Callers should be able to write `doc.ask("...")` and either block on the result or subscribe to streaming output. They should not have to write polling loops or SSE consumers themselves.
- **Type safety end-to-end.** The protocol records (shared with the server) flow through the client unchanged. Consumers work with `ValidationResult`, `AskResult`, etc. — not raw JSON.
- **Java-idiomatic.** Builder patterns where appropriate, `CompletableFuture` for async, `Stream` or `Flow` for streaming. No reactive-stack dependency in v0; just `java.util.concurrent`.

### 15.2 Core API surface

```java
// Construction
var anchor = AnchorClient.builder()
    .baseUrl("http://localhost:8080")
    .timeout(Duration.ofSeconds(120))
    .build();

// List and select
List<DocumentSummary> docs = anchor.listDocuments();
AnchorDocument doc = anchor.use(documentId);   // returns a doc-bound handle
// or
AnchorDocument doc = anchor.use("Smith et al. 2024");  // by title substring match

// Document-scoped operations (document_id is sent on every call automatically)
DocumentDetail detail = doc.describe();
RetrieveResponse hits = doc.retrieve("Does X inhibit Y?", 5);
ValidationResult judgment = doc.validate(chunkId, "Does X inhibit Y?");

// Async deliberation — blocking
AskResult result = doc.ask("Does X inhibit Y?").await();
// Async deliberation — streaming
doc.ask("Does X inhibit Y?")
    .onProposerThought(token -> System.out.print(token))
    .onCriticChallenge(challenge -> log.info(challenge))
    .onSynthesiserThought(token -> System.out.print(token))
    .onComplete(result -> renderFinal(result))
    .start();

// Cross-document — use the top-level client, not a doc handle
RetrieveResponse corpus = anchor.retrieve("...", 10);  // searches everything
```

The `AnchorDocument` handle is just a thin wrapper holding the `documentId` and a reference back to the `AnchorClient`. Calling `.use()` doesn't make a network round-trip — it's a pure client-side construction. This is *important*: it means listing 100 documents and creating handles for all of them is free.

If a caller wants to verify a handle resolves to an actually-existing document, they call `doc.describe()` which round-trips and throws on 404. This is opt-in, not automatic.

### 15.3 Async ask handle

```java
public interface AskHandle {
    // Subscribe to streaming events.
    AskHandle onStatus(Consumer<JobStatus> handler);
    AskHandle onProposerThought(Consumer<String> tokenHandler);
    AskHandle onProposerComplete(Consumer<ProposerOutput> handler);
    AskHandle onCriticChallenge(Consumer<String> challengeHandler);
    AskHandle onCriticComplete(Consumer<CriticOutput> handler);
    AskHandle onSynthesiserThought(Consumer<String> tokenHandler);
    AskHandle onComplete(Consumer<AskResult> handler);
    AskHandle onError(Consumer<Throwable> handler);

    // Initiate the deliberation. Returns immediately.
    void start();

    // Block until the deliberation completes; convenience for callers that
    // don't want streaming. Equivalent to start() + onComplete + waiting.
    AskResult await();
    AskResult await(Duration timeout);

    // Cancel an in-flight deliberation.
    void cancel();
}
```

The handle internally manages the SSE connection, parses event types, dispatches to registered consumers, and handles reconnect-with-replay if the SSE stream drops. None of that surfaces to the caller.

For consumers that don't want streaming, `await()` is a single line and produces a `CompletableFuture`-like blocking call. The implementation under the hood still uses SSE (it's how the server reports progress), but the caller is shielded from the streaming machinery.

### 15.4 Module layout (`anchor-client`)

```
io.aeyer.anchor.client
├── AnchorClient.java              # main entry, builder
├── AnchorDocument.java            # doc-bound handle
├── AskHandle.java                 # deliberation streaming interface
├── http                           # OkHttp wrapper, error handling, retries
├── sse                            # SSE consumer, event parsing, reconnect
├── exceptions                     # AnchorException, DocumentNotFoundException, etc.
└── internal                       # implementation details, not part of public API
```

Public API surface is small (under 30 classes). Heavy lifting is in `internal/`. Javadoc on the public classes is mandatory; `internal/` doesn't need it.

### 15.5 Dependencies

- `anchor-protocol` (project module)
- OkHttp 4.x — HTTP client + SSE support
- OkHttp SSE module — streaming
- Jackson Databind — JSON serialisation (already needed transitively)
- SLF4J API — logging facade (no impl bundled; users provide their own)

Deliberately *no*: Spring, Reactor, RxJava, kotlinx-coroutines. The client should be usable in any JVM project without dragging in a framework.

### 15.6 Versioning and stability

- v0.1.x — client API may change without notice. Built but not published.
- v0.2.0 — first published version. API stabilised, semver from this point.
- Breaking changes after v0.2.0 require a major version bump.

The protocol records (`anchor-protocol`) follow the same versioning. Server and client are independently versioned but both depend on protocol; protocol is the integration boundary.

### 15.7 Testing

- Unit tests for the client itself: HTTP plumbing, SSE event parsing, retry logic.
- Integration tests use a mock server (WireMock or similar) to simulate Anchor responses.
- End-to-end tests run the actual server in Testcontainers and exercise the client against it. These live in the `anchor-shell` module (since the shell is the most realistic end-to-end consumer).

### 15.8 Other-language clients (deferred)

Python and TypeScript clients are obvious follow-ons given the AI/RAG audience. Deferred to post-v0.2 because:
- Java client first establishes the API design under the most-typed conditions
- Python and TS can be largely auto-generated from the protocol records once shapes settle
- v0 audience is "developers willing to read a Java SDK" — fine for the writeup-driven launch

If demand emerges, Python is the natural next target. The protocol module's structure (separate concerns into sub-packages, no business logic) makes code-gen relatively painless.

---

## 16. Naming and identity

Working name: **Anchor**.

Maven coordinates: `io.aeyer:anchor-protocol`, `io.aeyer:anchor-server`, `io.aeyer:anchor-client`, `io.aeyer:anchor-shell` (groupId backed by domain `aeyer.io`).

Project identity: shipped under **ÆYER** as a coding persona, distinct from the author's primary engineering identity (`myrddian` on GitHub, professional CV under given name). ÆYER is also the author's electronic music project; the shared identity is intentional — the technical and artistic outputs are recognisably from the same sensibility.

Tagline candidates:
- "Anchor: chunks with their document's argument attached."
- "Anchor: source-grounded chunk validation as a primitive."
- "Stop misreading retrieved evidence."

Final name and tagline locked when v0.1.0 ships.

---

*End of v0 spec.*
