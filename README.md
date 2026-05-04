# Anchor

[![CI](https://github.com/myrddian/anchor/actions/workflows/ci.yml/badge.svg)](https://github.com/myrddian/anchor/actions/workflows/ci.yml)

> **Source-grounded chunk validation as a primitive.** v0 in progress — see
> [SPEC.md](SPEC.md) for the full design.

A retrieved chunk doesn't speak for its document. A paper's *"compound X
binds enzyme Y at K_i = 12 nM"* can be exactly the claim the discussion
section then demolishes. Vanilla RAG hands that chunk to a downstream
LLM with no warning; the LLM fluently mis-reads it; the reader trusts a
confident, grounded-looking, **wrong** answer.

Anchor exposes one primitive — *source-grounded chunk validation, given the
document's full argument* — through two interfaces shaped for two consumers:

- **`POST /validate`** — for **machines.** Synchronous JSON judgment with
  `argumentative_role` and `document_stance_on_query` enums. Branch on it.
  When a chunk is steelman-then-refuted or lives in a doc that *rejects*
  the query, the response also returns the chunks doing the refuting.
- **`POST /documents/{id}/ask`** — for **humans.** Async three-agent
  deliberation (proposer / critic / synthesiser) with **differentiated
  evidence access**, streamed token-by-token. The critic sees only the
  macro view (chapter + doc summaries); that asymmetry forces structural
  disagreement instead of paraphrase. The transparency *is* the trust.

Both back onto the same hierarchy: `documents → chapters → sections →
paragraphs → chunks` with claim-bearing summaries at each level. **Raw
text never appears in inputs to layers above paragraph summarisation**
(SPEC §4.5) — section / chapter / doc summaries see only the summaries
below them.

## Try it in 60 seconds

```bash
docker compose up -d postgres                    # pgvector on :5433
cp .env.example .env                             # set LLM_BASE_URL
./gradlew :anchor-server:bootRun                 # boots on :8090
```

Or run the whole stack in containers — server + postgres together:

```bash
docker compose --profile app up --build
```

Then drive the API directly:

```bash
# 1. Ingest a paper. Returns 202 + job_id; poll progress.
curl -X POST http://localhost:8090/ingest \
  -H 'Content-Type: application/json' \
  -d '{"source_path": "/abs/path/to/paper.pdf"}'
# → {"job_id":"7f...","progress_url":"/ingest/jobs/7f..."}

curl http://localhost:8090/ingest/jobs/7f...
# → {"status":"RUNNING","phase":"SUMMARISING_PARAGRAPHS","percent_complete":42, ...}

# 2. Once COMPLETED, validate a specific chunk against a query.
curl -X POST http://localhost:8090/validate \
  -H 'Content-Type: application/json' \
  -d '{"chunk_id":"<uuid>", "query":"compound X inhibits enzyme Y"}'
# → {"is_load_bearing":true, "argumentative_role":"STEELMAN_REFUTED_LATER",
#    "document_stance_on_query":"REJECTS", "alternative_chunks":[...]}

# 3. Or kick off a deliberation and stream the agents.
curl -X POST http://localhost:8090/documents/<doc-id>/ask \
  -H 'Content-Type: application/json' \
  -d '{"query":"does compound X inhibit enzyme Y?"}'
# → 202 {"job_id":"a1...","stream_url":"/jobs/a1.../stream"}

curl -N http://localhost:8090/jobs/a1.../stream     # SSE
```

The full API is documented at **<http://localhost:8090/swagger-ui/index.html>**
(spec at `/v3/api-docs`) — the contract for SDK consumers and integrators.

## SDKs

Three first-party SDKs, same surface, language-idiomatic ergonomics:

```java
// Java
AnchorClient client = AnchorClient.builder()
        .baseUrl("http://localhost:8090")
        .apiToken(System.getenv("ANCHOR_API_TOKEN"))   // optional
        .build();

AnchorDocument doc = client.use("Smith2024");          // by title or UUID
ValidateResponse v = doc.validate(chunkId, "compound X inhibits enzyme Y");

AskHandle handle = doc.ask("does compound X inhibit enzyme Y?");
handle.subscribe(event -> render(event));              // live SSE
AskJobResponse result = handle.await(Duration.ofMinutes(2));
```

```python
# Python — pip install -e anchor-client-python
from anchor_client import AnchorClient

client = AnchorClient(base_url="http://localhost:8090", api_token=...)
doc = client.use(title_substring="Smith2024")
result = doc.ask("does compound X inhibit enzyme Y?").await_completion()
print(result["final_response"])
```

```js
// Node 18+ — ESM, zero dependencies
import { AnchorClient } from "@aeyer/anchor-client";

const client = new AnchorClient({ baseUrl: "http://localhost:8090", apiToken: ... });
const doc = await client.use({ titleSubstring: "Smith2024" });
const handle = await doc.ask("does compound X inhibit enzyme Y?");
for await (const event of handle.streamEvents()) { /* ... */ }
```

[anchor-client/](anchor-client/) · [anchor-client-python/](anchor-client-python/) · [anchor-client-node/](anchor-client-node/)

## Try it in a browser

A single-page UI ships at **<http://localhost:8090/>** — pick a document,
type a question, watch the proposer / critic / synthesiser deliberate
live. **Useful for sanity-checking ingestion and giving non-developers a
look,** but the API is what you're integrating. Disable for hardened
deployments with `ANCHOR_WEB_UI_ENABLED=false`.

## Configuration

```bash
# Inference (any OpenAI-compatible endpoint — LM Studio, real OpenAI,
# vLLM, llama.cpp's HTTP server, ollama's OpenAI shim, …)
LLM_BASE_URL=http://mac-studio.local:1234/v1
LLM_CHAT_MODEL=gemma-3-4b-it
LLM_EMBEDDING_MODEL=nomic-embed-text-v1.5             # 768-dim required
LM_STUDIO_API_KEY=                                    # bearer; empty = no auth

# Server
ANCHOR_API_TOKEN=                                     # empty = open dev mode
ANCHOR_WEB_UI_ENABLED=true
ANCHOR_OPENAPI_ENABLED=true

# Postgres (defaults match docker-compose.yml)
ANCHOR_DB_URL=jdbc:postgresql://localhost:5433/anchor
```

Full reference: [.env.example](.env.example). The Gradle build auto-loads
`.env` for `bootRun` and the test suite — no shell sourcing needed.

## Status

| Phase | Status |
|---|---|
| 0 — Spike (manual chemist eyeball) | Pending |
| 1 — Foundations + Ingest | **Done** |
| 2 — `/validate` + Document resource | **Done** |
| 3 — Deliberation core (`/ask` + jobs) | **Done** |
| 4 — SSE + `/retrieve` + SDK + shell | **Done** |
| 5 — Writeup + tag v0.1.0 | Open |
| 6 — Maven Central / npm / PyPI | Open |

**Not stable until v0.2.0.** Nothing published yet; install from this
checkout. ~90 unit + integration tests; the integration suite is gated on a
pgvector instance reachable on `localhost:5433`.

## Stack

Java 21, Spring Boot 3.3.x, Postgres 16 + pgvector (HNSW cosine), Apache
PDFBox 3.x + Apache Tika 2.9.x for ingest, OkHttp + Jackson for the
inference client, springdoc for OpenAPI, Flyway for migrations, MapStruct,
JUnit 5 + Testcontainers. Apache 2.0.

## Documentation

- [SPEC.md](SPEC.md) — full v0 design (the source of truth).
- [docs/architecture.md](docs/architecture.md) — module/package boundaries, DBO/domain/DTO discipline, worker pools.
- [docs/prompts.md](docs/prompts.md) — the eight prompts and their tuning protocol.
- [docs/client-usage.md](docs/client-usage.md) — Java SDK long form + async patterns.
- [docs/evaluation.md](docs/evaluation.md) — corpus, success criteria, eyeball protocol.
- Live: <http://localhost:8090/swagger-ui/index.html> when the server is running.

## Repository layout

```
anchor/
├── SPEC.md                       v0 specification (source of truth)
├── CLAUDE.md                     Agent orientation
├── docker-compose.yml            Postgres 16 + pgvector
├── anchor-protocol/              Shared request/response records, enums
├── anchor-server/                Spring Boot service
├── anchor-client/                Java SDK
├── anchor-client-python/         Python SDK
├── anchor-client-node/           Node.js SDK (ESM, zero deps)
├── anchor-shell/                 Spring Shell harness — `./gradlew :anchor-shell:bootRun`
└── docs/                         Architecture, prompts, client usage, evaluation
```

## Licence

Apache 2.0. See [LICENCE](LICENCE) and [NOTICE](NOTICE).
