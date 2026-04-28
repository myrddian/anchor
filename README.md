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
- `POST /documents/{id}/ask` — for **humans**. An async three-agent
  deliberation (proposer / critic / synthesiser) with differentiated evidence
  access, streamed token-by-token over SSE. The critic sees only the macro
  view, which forces structural disagreement rather than paraphrase. The
  transparency *is* the trust mechanism.

Both back onto the same hierarchy: `documents → chapters → sections →
paragraphs → chunks`, with claim-bearing summaries at each level.

**Mental model:** documents-as-databases. Each ingested document is the unit of
query. You "connect" to a document the way you connect to a Postgres DB,
interrogate it, and either get structured judgment or first-person grounded
prose.

## Status

Pre-MVP. The repo currently contains the v0 specification and project
scaffolding only — no working code yet. See [SPEC.md](SPEC.md) §12 for the
phased execution plan and [CLAUDE.md](CLAUDE.md) for an agent-oriented map.

The client SDK API is **not stable** until v0.2.0; nothing is published to
Maven Central yet.

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

## Quickstart (planned — not yet runnable)

The commands below describe the v0.1 target. They will not work until Phase 1
is complete.

```bash
# Postgres
docker compose up -d postgres

# Server (point at your LM Studio instance via env var)
LM_STUDIO_BASE_URL=http://mac-studio.local:1234/v1 \
  ./gradlew :anchor-server:bootRun

# Shell
./gradlew :anchor-shell:run

anchor> ingest /path/to/paper.pdf
anchor> use <doc_id>
smith2024> ask "Does compound X inhibit enzyme Y?"
```

## Stack

Java 21, Spring Boot 3.3.x, Postgres 16 + pgvector, Apache PDFBox 3.x, OkHttp +
Jackson against an OpenAI-compatible LM Studio endpoint, Flyway, MapStruct,
JUnit 5 + Testcontainers. Apache 2.0 + NOTICE.

LM Studio runs separately. Default chat model: Gemma 4 E4B. Embedding model:
`nomic-embed-text-v1.5` (768-dim).

## Documentation

- [Specification](SPEC.md) — full v0 design.
- [Architecture](docs/architecture.md) — module/package boundaries, DBO/domain/DTO discipline, worker pools.
- [Prompts](docs/prompts.md) — the eight prompts and their tuning protocol.
- [Client usage](docs/client-usage.md) — SDK quickstart and async patterns.
- [Evaluation](docs/evaluation.md) — corpus, success criteria, eyeball protocol.

## Contributing

This is a personal research project. PRs are welcome but support is not
guaranteed; see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache 2.0. See [LICENCE](LICENCE) and [NOTICE](NOTICE).
