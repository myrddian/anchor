# Security policy

## Supported versions

Anchor is in **v0** and not yet under a stability or security-support
SLA. The `main` branch is the only line that receives fixes; older
tags / branches are point-in-time snapshots and won't get backported
patches.

| Version  | Supported          |
| -------- | ------------------ |
| `main`   | :white_check_mark: |
| < `v0.1` | :x:                |

That said, **vulnerabilities are taken seriously regardless of v0
status** — see "Reporting" below.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security reports.**

Use GitHub's private vulnerability reporting flow:

[**Open a private security advisory →**](https://github.com/myrddian/anchor/security/advisories/new)

This is the same channel used for [Code of Conduct](CODE_OF_CONDUCT.md)
enforcement reports — it's confidential, no email exposure on either
side, and the maintainer is notified directly.

### What to include

- **A clear description of the issue** and which component it affects
  (server / SDK / MCP endpoint / etc.)
- **Reproduction steps** — the smallest example that demonstrates the
  vulnerability.
- **Affected versions / commit** — `main @ <sha>` is fine.
- **Impact** as you understand it — what an attacker could do,
  realistic worst case.
- **Optional: a suggested fix** — not required, but appreciated.

### What to expect

- Acknowledgement within **3 working days**.
- An initial assessment within **7 working days** — whether the
  report is in scope, severity rating, expected fix timeline.
- Coordinated disclosure: a CVE / advisory will be published once a
  fix is released, with credit to the reporter unless you prefer to
  remain anonymous.
- For non-critical issues, fixes typically land in the next regular
  release. For critical issues, an out-of-band patch.

## Scope

In scope (please report):

- Authentication / authorization bypasses on the HTTP API
  (`AnchorApiTokenFilter` and the bearer-token model)
- SQL injection, path traversal, or similar in the ingest / search /
  validate paths
- SSRF or arbitrary-URL fetch via `LLM_BASE_URL` or upload pathways
- Vulnerabilities in the MCP transport (`/mcp`) or the SSE stream
  endpoints
- Unsafe deserialisation, RCE on the server JVM
- Information disclosure beyond what `/actuator/info` and the OpenAPI
  spec are documented to expose
- Significant DoS surfaces (e.g. unbounded resource consumption from
  a single request)

Out of scope (probably won't be acted on):

- Reports against dependencies that already have published advisories
  and a documented upgrade path — those are tracked via Dependabot
  rather than this channel.
- Issues that require a malicious operator (e.g. "if the LLM
  endpoint is hostile") — bringing your own LLM is an explicit
  trust assumption.
- Theoretical attacks without a reproducible PoC.
- Best-practice suggestions that aren't actual vulnerabilities
  (file as a regular issue instead).

## Hardening notes

A few defaults worth knowing about, since they affect what counts as
a vulnerability:

- The web UI and the MCP endpoint share the same `ANCHOR_API_TOKEN`
  bearer model. When the token is unset (the default for local dev),
  the service is wide-open — that's deliberate, not a bug. Set
  `ANCHOR_API_TOKEN` before exposing Anchor on a non-loopback
  interface.
- The bearer-comparison in `AnchorApiTokenFilter` uses constant-time
  byte comparison; timing oracles on token length / value are not in
  scope for reports.
- v0 is single-tenant. Per-document ACLs, per-user quotas, and
  rate-limiting are explicit non-goals for v0 (tracked in
  [`docs/follow-ups.md`](docs/follow-ups.md) Tier 3). Reports framed
  as "any token holder can read every document" will be acknowledged
  but treated as design, not vulnerability.

## Crediting reporters

Unless you ask to remain anonymous, the published advisory will name
you and link your GitHub profile. Anonymous reports are accepted.
