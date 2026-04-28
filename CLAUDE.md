# Anchor — agent orientation

`SPEC.md` is the source of truth for v0. Read it before any non-trivial work. This file is the high-signal index, not a substitute.

## What this is

A Spring Boot service exposing **one primitive — source-grounded chunk validation — through two interfaces optimised for different consumers**:

- `POST /validate` — **LLM-to-LLM**. Synchronous, deterministic JSON judgment about whether a chunk is load-bearing for a query, given the document's full argument. Enums (`argumentative_role`, `document_stance_on_query`), short reasoning, machine-parseable.
- `POST /documents/{id}/ask` — **human-facing**. Async three-agent deliberation (proposer → critic → synthesiser) with **differentiated evidence access** (proposer/synthesiser see full hierarchy, critic sees macro view only). SSE-streamed token-by-token. The deliberation is the trust mechanism, not theatre.

Both back onto the same hierarchical claim-bearing summarisation: `documents → chapters → sections → paragraphs → chunks`, summaries at every level, embeddings on chunks. The critical compression rule: **raw text never appears in inputs to layers above paragraph summarisation** (SPEC §4.5).

Mental model: **documents-as-databases**. Each ingested document is the unit of query (analogous to `psql` connecting to one DB on a multi-DB server). Cross-corpus retrieval exists but is the exception. The "select a document, then query it" UX lives in the **client**; the server is stateless apart from the in-memory job store for in-flight `/ask` deliberations.

## Stack

Java 21, Spring Boot 3.3.x, Gradle (Kotlin DSL), Spring Data JPA + Hibernate, Postgres 16 + pgvector (768-dim, HNSW cosine), Apache PDFBox 3.x, hand-rolled OkHttp+Jackson client against LM Studio's OpenAI-compatible API, Spring Shell for the demo harness, MapStruct for mapping, Flyway for migrations, JUnit 5 + Testcontainers + Mockito. Apache 2.0 + NOTICE.

LM Studio runs on a separate Mac Studio over LAN. Chat: Gemma 4 E4B. Embedding: `nomic-embed-text-v1.5`.

## Multi-module layout — Maven groupId `io.aeyer`

| Module | Purpose | Published |
|---|---|---|
| `anchor-protocol` | Shared request/response records, enums. Pure POJOs, Jackson + JSR-305 only. | v0.2 |
| `anchor-server` | Spring Boot service. Depends on `anchor-protocol`. | No |
| `anchor-client` | Java SDK. Holds document binding client-side, handles SSE/polling/retries. | v0.2 |
| `anchor-shell` | Spring Shell harness. Depends on `anchor-client` (forces client API ergonomics). | No |

Server packages: `api / service / domain / persistence/{entity,repo,mapper} / apimapper / llm / ingest / jobs / sse / workers / config`.

## Non-negotiables — re-read before changing layering

1. **Strict DBO / domain / DTO separation** (SPEC §7.1). DBOs are JPA entities suffixed `Dbo`, mutable, lazy, never escape persistence. Domain records are pure immutable Java records, eagerly populated, no JPA/Jackson/Spring — these cross thread boundaries into worker pools. DTOs live in `anchor-protocol`, the wire format, never enter the service layer. Repositories return DBOs internally and convert to domain inside their own transaction (`...AsDomain` methods). Services take/return domain. Controllers convert DTO ↔ domain at the API boundary via `apimapper`. **This is what makes async deliberation work without `LazyInitializationException`** — it is load-bearing, not bureaucracy.

2. **Worker pool architecture** (SPEC §7.9). Pools segment by **inference resource**, not role: one `chat` pool (the single Gemma chat slot), one `embedding` pool (nomic, 2 slots), plus orchestration pools `deliberation` (4) and `ingest` (1) that submit work into the inference pools and block on `.get()`. Orchestrator threads are cheap and mostly waiting on chat — that's the whole point: orchestration concurrency and inference concurrency are separately tunable. Thread names (`chat-worker-0`, `deliberation-worker-2`, …) are mandatory for log correlation. Shutdown order: orchestration pools first, then inference pools.

3. **Three-agent evidence asymmetry** (SPEC §6.6). Critic must see only chapter summaries + doc summary — *not* sections, paragraphs, or chunks. A critic with the same evidence as the proposer is a paraphrase generator. The asymmetry is the design.

4. **Server is stateless per request** (SPEC §5). Every endpoint takes `document_id` explicitly. No sessions, no `X-Anchor-Session` header. The "use a doc" pattern is an `AnchorDocument` handle in the client (SPEC §15) holding the id; calling `.use()` makes no server round-trip.

5. **Prompts live in `anchor-server/src/main/resources/prompts/*.txt`**, loaded via `@Value("classpath:...")`. Tune via §6.7 protocol (5 cases, chemist eyeballs, lock at ≥80%).

## API surface (v0)

`POST /ingest`, `POST /validate`, `POST /retrieve`, `GET /documents`, `GET /documents/{id}`, `POST /documents/{id}/ask` (returns 202 + job_id), `GET /jobs/{id}`, `GET /jobs/{id}/stream` (SSE with reconnect-replay), `DELETE /jobs/{id}`, `GET /chunks/{id}`, `GET /health`. No `/v1/` prefix in v0.

`/ask` SSE event types: `status`, `proposer_thought`, `proposer_complete`, `critic_thought`, `critic_complete`, `synthesiser_thought`, `completed`. Job statuses: `QUEUED, PROPOSING, CRITIQUING, SYNTHESISING, COMPLETED, FAILED, CANCELLED`.

## Phased execution (SPEC §12)

Phase 0 spike (Anthropic API hand-built deliberation, gates pass) → Phase 1 foundations + ingest (multi-module, DBO/domain split, worker pools, LM Studio client, ingest pipeline) → Phase 2 validate + document resource → Phase 3 deliberation core → Phase 4 SSE + client + shell + retrieve → Phase 5 writeup → Phase 6 publish to Maven Central.

The DBO/domain/worker-pool foundations in Phase 1 are load-bearing for Phase 3 — do not skip or soften them to save time.

## When unsure

- Layering / mapping question → SPEC §7.1.
- Pool sizing / shutdown / dedup → SPEC §7.9.
- Prompt or eval criterion → SPEC §6 + §6.7.
- Endpoint contract → SPEC §5.
- Schema → SPEC §3.

If a decision conflicts with the spec, raise it before coding around it.
