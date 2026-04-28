# Contributing to Anchor

Anchor is a personal research project shipped under the **ÆYER** identity. PRs
are welcome, but please read this before opening one — it will save us both
time.

## Status and expectations

- **Pre-v0.2 the API is unstable.** Anything in `anchor-protocol` or
  `anchor-client` may change without notice. Don't build production systems on
  it yet.
- **Support is not promised.** Issues will be read; not all will be responded
  to or actioned. Triage is best-effort.
- The author has a day job and other long-running creative projects. Cadence
  is "weekend work post-loops"; do not expect 24-hour turnarounds.

## Where things live

- [SPEC.md](SPEC.md) is the source of truth for v0 design decisions. If a
  change conflicts with the spec, raise it in an issue *before* coding.
- [CLAUDE.md](CLAUDE.md) is the high-signal index for AI agents and humans
  who want to skim the project structure.
- [docs/architecture.md](docs/architecture.md) covers module/package
  boundaries, DBO/domain/DTO discipline, and worker pools.

## Non-negotiables (from SPEC §7)

These design choices are load-bearing — they exist for documented reasons in
the spec. PRs that soften them will be rejected unless they come with a
spec-level argument.

1. **Strict DBO / domain / DTO separation.** No JPA proxies cross thread
   boundaries. Repositories return domain records via `...AsDomain` methods
   inside their own transactions.
2. **Pool segmentation by inference resource.** Chat pool, embedding pool,
   plus orchestration pools (`deliberation`, `ingest`) that submit into
   them and block on `.get()`.
3. **Critic sees only macro view.** Equal evidence makes the critic a
   paraphrase generator; the asymmetry is the design.
4. **Server is stateless per request.** Every endpoint takes
   `document_id` explicitly. The "select a document" UX is a *client*
   concern.

## Code style

- Java 21. Use records for immutable data, pattern matching for enum dispatch.
- MapStruct for DBO ↔ domain and domain ↔ DTO mapping.
- No emoji in code, comments, or commit messages.
- Comments should explain *why*, not *what*. No reference to "the current
  task" or "added for X" — that belongs in commit messages and PR descriptions.

## Commits

- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`,
  `test:`).
- One logical change per commit; the project tags `v0.1.0` when the demo
  harness produces SPEC §10.2 output reliably.

## Running tests

(Targets will exist once Phase 1 lands.)

```bash
./gradlew test                                # unit + component
./gradlew :anchor-server:integrationTest      # Testcontainers
```

## Reporting issues

Please include:

- Phase / module affected.
- LM Studio model versions if relevant (chat + embedding).
- Postgres version (must be 16+ for pgvector HNSW).
- Minimal reproduction.

## Licence of contributions

By submitting a PR you agree to license the contribution under Apache 2.0,
matching the rest of the project (see [LICENCE](LICENCE) and [NOTICE](NOTICE)).
