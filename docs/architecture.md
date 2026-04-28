# Architecture

> Placeholder. The full architecture is currently described in
> [SPEC.md](../SPEC.md) §2 and §7. This document will be expanded once Phase 1
> code lands; for now, treat the spec as authoritative.

## Quick orientation

- **Modules** — `anchor-protocol` (shared records), `anchor-server` (Spring
  Boot), `anchor-client` (Java SDK), `anchor-shell` (Spring Shell harness).
- **Layering** — strict DBO / domain / DTO separation. JPA entities never
  escape the persistence layer; immutable domain records cross thread
  boundaries; protocol DTOs live only at the API edge. See SPEC §7.1.
- **Worker pools** — segmented by inference resource: chat, embedding, plus
  orchestration pools (`deliberation`, `ingest`) that submit into them.
  See SPEC §7.9.
- **Two interfaces** — `/validate` (LLM-to-LLM, sync) and
  `/documents/{id}/ask` (human-facing, async with three-agent deliberation).
  See SPEC §5.

## Sections to fill in (post-Phase 1)

- Module dependency graph and concrete diagrams beyond the ASCII in SPEC §2.
- Boot sequence, bean wiring, configuration property surface.
- Request lifecycle for each endpoint with thread-pool annotations.
- Failure modes and recovery (LM Studio outage, Postgres outage, etc.).
- How to add a new endpoint without violating the DBO/domain/DTO boundary.
