# Anchor

> Source-grounded chunk validation as a primitive. Two interfaces, two consumers,
> one hierarchy. *Working name; v0 in progress — see [SPEC.md](SPEC.md).*

A retrieved chunk doesn't speak for its document. A paper's "compound X binds
enzyme Y at K_i = 12 nM" can be exactly the claim the discussion section then
demolishes. Vanilla RAG hands that chunk to a downstream LLM with no warning;
the LLM fluently mis-reads it; the reader trusts a confident, grounded-looking,
*wrong* answer.

Anchor exposes one primitive — **source-grounded chunk validation, given the
document's full argument** — through two interfaces, each shaped for a
different consumer:

- `POST /validate` — for **machines**. Synchronous JSON judgment with
  `argumentative_role` and `document_stance_on_query` enums. Branch on it.
  When the model flags the chunk as steelman-then-refuted or as living in a
  document that *rejects* the query, the response also includes the chunks
  doing the actual refuting (alternative-chunk discovery via cosine search on
  the negated query).
- `POST /documents/{id}/ask` — for **humans**. An async three-agent
  deliberation (proposer / critic / synthesiser) with **differentiated
  evidence access**, streamed token-by-token over SSE. The critic sees only
  the macro view (chapter + doc summaries), which forces structural
  disagreement rather than paraphrase. The transparency *is* the trust
  mechanism.

Both back onto the same hierarchy: `documents → chapters → sections →
paragraphs → chunks`, with claim-bearing summaries at each level. The
critical compression rule (SPEC §4.5): **raw text never appears in inputs to
layers above paragraph summarisation.** Section / chapter / doc summaries see
only the summaries below them.

**Mental model:** documents-as-databases. Each ingested document is the unit
of query. You "connect" to a document the way you connect to a Postgres DB,
interrogate it, and either get structured judgment or first-person grounded
prose.

## Why this is different

Most RAG systems treat retrieval as a single-shot lookup: top-K chunks → stuff
into a prompt → call the model. Anchor refuses that simplification at three
points:

1. **Retrieval is opinionated about its consumer.** A machine wants enums it
   can branch on. A human wants reasoning it can audit. Same backend, two
   shapes.
2. **The deliberation is the trust mechanism.** Anchor doesn't claim the
   synthesiser's answer is correct — it claims the deliberation transcript is
   honest. The critic-with-restricted-evidence is the structural lever that
   keeps the synthesiser from agreeing with the proposer too easily.
3. **Documents are databases, not corpora.** Cross-corpus retrieval exists
   but the optimisation target is *deeply* understanding one document at a
   time. Every endpoint takes `document_id` explicitly.

## Status

**v0 in progress — Phase 1–4 server-side and SDK/shell are landed.**

| Phase | Status | Tickets |
|---|---|---|
| 0 — Spike (manual chemist eyeball) | Pending | ANC-1, ANC-2 |
| 1 — Foundations + Ingest | **Done** | ANC-3..ANC-7 |
| 2 — /validate + Document resource | **Done** | ANC-9, ANC-10, ANC-11 |
| 3 — Deliberation core (/ask + jobs) | **Done** | ANC-12..ANC-15 |
| 4 — SSE + /retrieve + SDK + shell | **Done** | ANC-16..ANC-19 |
| 5 — Writeup + tag v0.1.0 | Open | ANC-20, ANC-21 |
| 6 — Maven Central | Open | ANC-22..ANC-24 |

87 tests; 18 of them are integration tests gated on a pgvector instance
reachable on `localhost:5433`. The integration suite passes against
`pgvector/pgvector:pg16`.

The client SDK API is **not stable** until v0.2.0; nothing is published to
Maven Central yet.

## Endpoints

| Verb | Path | Purpose |
|---|---|---|
| POST | `/ingest` | Ingest a PDF; idempotent on content hash. |
| GET | `/documents` | List ingested documents (paginated, `q=` substring filter). |
| GET | `/documents/{id}` | Document detail (chapters + sections, no raw text). |
| GET | `/chunks/{id}` | Chunk text + full ancestor chain. |
| POST | `/validate` | Judgment on a chunk vs. a query. Includes alternative chunks when steelman-refuted. |
| POST | `/retrieve` | Semantic retrieval (Shape 1 — chunks wrapped with full ancestor stack). |
| POST | `/documents/{id}/ask` | Start a three-agent deliberation. Returns 202 + `job_id`. |
| GET | `/jobs/{id}` | Current deliberation envelope (status, agent slots, final response). |
| GET | `/jobs/{id}/stream` | SSE stream of status + thought tokens + completion events; supports reconnect-replay. |
| DELETE | `/jobs/{id}` | Best-effort cancel. |
| GET | `/health` | Standard Spring actuator. |

## Quickstart

### 1. Postgres

```bash
docker compose up -d postgres
```

This brings up `pgvector/pgvector:pg16` on **host port 5433** (not the
postgres default 5432) with database `anchor`, user `anchor`, password
`anchor`. The non-default port keeps Anchor from fighting other local
postgres instances on 5432.

### 2. LM Studio

Run LM Studio anywhere on your LAN with **chat:** Gemma 4 E4B (one slot) and
**embedding:** `nomic-embed-text-v1.5` (768-dim, two slots). Note the OpenAI-
compatible base URL.

### 3. Server

```bash
LM_STUDIO_BASE_URL=http://mac-studio.local:1234/v1 \
  ./gradlew :anchor-server:bootRun
```

Server boots on `:8080` by default. Watch the startup log for the named
worker threads (`chat-worker-0`, `embedding-worker-0..1`,
`deliberation-worker-0..3`, `ingest-worker-0`) — that's how SPEC §7.9 thread
correlation surfaces in practice.

### 4. Shell (interactive)

```bash
./gradlew :anchor-shell:bootRun

anchor:> ingest /path/to/paper.pdf
anchor:> list
anchor:> use Smith2024     # or use <uuid>
anchor:> describe
anchor:> retrieve "does compound X inhibit enzyme Y" --k 5
anchor:> validate <chunk-uuid> "compound X inhibits enzyme Y"
anchor:> ask "does compound X inhibit enzyme Y"
anchor:> demo "does compound X inhibit enzyme Y"   # /retrieve + /ask side-by-side
```

### 5. SDK (Java)

```java
AnchorClient anchor = AnchorClient.builder()
    .baseUrl("http://localhost:8080")
    .timeout(Duration.ofSeconds(120))
    .build();

AnchorDocument doc = anchor.use("Smith2024");

// Synchronous judgment for an LLM-driven loop.
ValidateResponse v = doc.validate(chunkId, "compound X inhibits enzyme Y");
switch (v.argumentativeRole()) { ... }

// Async deliberation for a human-facing UI.
AskHandle ask = doc.ask("does compound X inhibit enzyme Y");
ask.subscribe(event -> render(event));         // SSE token stream
AskJobResponse final = ask.await(Duration.ofMinutes(2));
```

See [docs/client-usage.md](docs/client-usage.md) for the long form.

## Running tests

```bash
./gradlew test                      # unit tests, no Docker required
docker compose up -d postgres       # for integration tests
./gradlew test                      # now includes the gated integration suite
```

Integration tests probe `localhost:5433` for a pgvector instance with the
`vector` extension installed and skip gracefully if absent. Override the
target with `ANCHOR_TEST_POSTGRES_URL=jdbc:postgresql://host:port/db`.

## Stack

Java 21, Spring Boot 3.3.x, Postgres 16 + pgvector, Apache PDFBox 3.x, OkHttp +
Jackson against an OpenAI-compatible LM Studio endpoint, Flyway, MapStruct,
JUnit 5 + Testcontainers. Apache 2.0 + NOTICE.

LM Studio runs separately. Default chat model: Gemma 4 E4B. Embedding model:
`nomic-embed-text-v1.5` (768-dim).

## Repository layout

```
anchor/
├── SPEC.md                       v0 specification (source of truth)
├── CLAUDE.md                     Agent orientation
├── LICENCE                       Apache 2.0
├── NOTICE                        Attribution
├── docker-compose.yml            Postgres 16 + pgvector
├── settings.gradle.kts           Multi-module include
├── build.gradle.kts              Root build
├── docs/                         Architecture, prompts, client usage, evaluation
├── anchor-protocol/              Shared request/response records, enums
├── anchor-server/                Spring Boot service
├── anchor-client/                Java SDK
├── anchor-shell/                 Spring Shell harness
└── test-corpus/                  Local chemistry papers (gitignored)
```

## Documentation

- [Specification](SPEC.md) — full v0 design.
- [Architecture](docs/architecture.md) — module/package boundaries, DBO/domain/DTO discipline, worker pools.
- [Prompts](docs/prompts.md) — the eight prompts and their tuning protocol.
- [Client usage](docs/client-usage.md) — SDK quickstart and async patterns.
- [Evaluation](docs/evaluation.md) — corpus, success criteria, eyeball protocol.
- [Backlog](docs/backlog.md) — Linear mirror of the v0 plan.

## Contributing

This is a personal research project. PRs are welcome but support is not
guaranteed; see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache 2.0. See [LICENCE](LICENCE) and [NOTICE](NOTICE).
