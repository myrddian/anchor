# Backlog

Work items live in **Linear**: project [Anchor](https://linear.app/aletheia-workspace/project/anchor-0da4ebc26227) under team **Anchor** (key `ANC`). Issues are referenced as `ANC-N` in commit messages and PR titles.

This file is the **read-only mirror** of the v0 plan. Linear is the source of truth for status changes; do not edit issue scopes here, edit them in Linear.

## Milestones (SPEC §12)

| Milestone | Target | Gate |
|---|---|---|
| Phase 0 — Spike | 2026-05-04 | Critic catches the steelman flip with macro view only |
| Phase 1 — Foundations + Ingest | 2026-05-18 | One paper ingested end-to-end; `chat-worker-0` / `embedding-worker-N` visible in logs |
| Phase 2 — Validate + Document Resource | 2026-06-01 | ≥80% chemist-eyeball correctness on `argumentative_role` for steelman cases |
| Phase 3 — Deliberation Core | 2026-06-22 | Critic catches steelman flip; synthesiser refuses ≥2 of 3 off-topic queries |
| Phase 4 — SSE + Client + Shell | 2026-07-13 | `demo` command produces SPEC §10.2 output |
| Phase 5 — Writeup | 2026-07-27 | `v0.1.0` tagged; blog post + cross-post live |
| Phase 6 — Client polish + Maven Central | 2026-08-31 | `io.aeyer:anchor-{protocol,client}:0.2.0` on Maven Central |

## Issues

### Phase 0 — Spike

- **[ANC-1](https://linear.app/aletheia-workspace/issue/ANC-1)** — Hand-build four-level summary hierarchy on one chemistry paper
- **[ANC-2](https://linear.app/aletheia-workspace/issue/ANC-2)** — Hand-craft three-agent deliberation against Anthropic API on a steelman case

### Phase 1 — Foundations + Ingest

- **[ANC-3](https://linear.app/aletheia-workspace/issue/ANC-3)** — Persistence layer with strict DBO/domain split (MapStruct mappers)
- **[ANC-4](https://linear.app/aletheia-workspace/issue/ANC-4)** — WorkerPools service (chat / embedding / deliberation / ingest)
- **[ANC-5](https://linear.app/aletheia-workspace/issue/ANC-5)** — LM Studio client (blocking + streaming)
- **[ANC-6](https://linear.app/aletheia-workspace/issue/ANC-6)** — PDF parsing, chapter/section detection, chunking
- **[ANC-7](https://linear.app/aletheia-workspace/issue/ANC-7)** — Summarisation pipeline + POST /ingest
- **[ANC-8](https://linear.app/aletheia-workspace/issue/ANC-8)** — Phase 1 smoke test — ingest one paper end-to-end

### Phase 2 — Validate + Document Resource

- **[ANC-9](https://linear.app/aletheia-workspace/issue/ANC-9)** — Validation prompt locked via §6.7 + JSON parsing
- **[ANC-10](https://linear.app/aletheia-workspace/issue/ANC-10)** — POST /validate endpoint + alternative-chunk discovery
- **[ANC-11](https://linear.app/aletheia-workspace/issue/ANC-11)** — GET /documents (list) and GET /documents/{id} (detail)

### Phase 3 — Deliberation Core

- **[ANC-12](https://linear.app/aletheia-workspace/issue/ANC-12)** — Tune three deliberation prompts in order: proposer → critic → synthesiser
- **[ANC-13](https://linear.app/aletheia-workspace/issue/ANC-13)** — JobStore (in-memory) + lifecycle + watchdog
- **[ANC-14](https://linear.app/aletheia-workspace/issue/ANC-14)** — AskService — deliberation orchestrator + DocumentContext builder
- **[ANC-15](https://linear.app/aletheia-workspace/issue/ANC-15)** — Ask endpoints — POST /documents/{id}/ask, GET /jobs/{id}, DELETE /jobs/{id}

### Phase 4 — SSE + Client + Shell

- **[ANC-16](https://linear.app/aletheia-workspace/issue/ANC-16)** — SSE emitter + JobStreamRegistry + GET /jobs/{id}/stream with reconnect-replay
- **[ANC-17](https://linear.app/aletheia-workspace/issue/ANC-17)** — POST /retrieve (Shape 1)
- **[ANC-18](https://linear.app/aletheia-workspace/issue/ANC-18)** — anchor-client SDK — AnchorClient, AnchorDocument, AskHandle
- **[ANC-19](https://linear.app/aletheia-workspace/issue/ANC-19)** — anchor-shell with demo command (the §10.2 artifact)

### Phase 5 — Writeup

- **[ANC-20](https://linear.app/aletheia-workspace/issue/ANC-20)** — Blog post draft — two-audiences thesis
- **[ANC-21](https://linear.app/aletheia-workspace/issue/ANC-21)** — README polish + cross-post + tag v0.1.0

### Phase 6 — Client polish + Maven Central

- **[ANC-22](https://linear.app/aletheia-workspace/issue/ANC-22)** — Client API polish + Javadoc
- **[ANC-23](https://linear.app/aletheia-workspace/issue/ANC-23)** — Sonatype Central + DNS verification on aeyer.io
- **[ANC-24](https://linear.app/aletheia-workspace/issue/ANC-24)** — Publish v0.2.0 to Maven Central + second writeup

## Conventions

- Reference issues in commits: `feat(ingest): add chunker [ANC-6]`.
- Reference issues in PR titles for auto-link in Linear.
- Status changes happen in Linear, not in this file.
- New work that doesn't fit an existing issue: open a new Linear ticket first, then code.
