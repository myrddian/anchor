# Follow-up backlog

The state after the cross-replica dedup + observability landing
(commit `2593da8`). Phase 0 is the empirical close (chemist eyeball
per SPEC §6.7); everything below is engineering / polish.

Items are roughly ranked by leverage within their tier.

---

## Tier 1 — should land before any wider release

These are the things I'd lose sleep over if someone evaluating the repo
asked "does this work in production." Each is bounded to a single
afternoon at most.

- [ ] **Structured logging.** Spring's default text format is fine for
      tail-following but useless for ingest into Loki / Datadog / Cloud
      Logging. Drop `logstash-logback-encoder` into the runtime classpath,
      add `logback-spring.xml` with a JSON appender activated by a
      `json-logs` profile. Include the trace_id / span_id from MDC so
      log lines correlate to spans. **~30 min.**
- [ ] **anchor-shell sanity sweep.** The shell module hasn't been
      touched this session — its `AnchorClient` calls are back-compat'd
      but unverified. Spin it up against the demo doc and walk through
      `ingest`, `list`, `use`, `ask`. Likely zero-bug, but unverified
      means "broken until proven otherwise." **~15 min.**
- [ ] **Push the branch + open a draft PR.** Hiring reviewers can't
      see local commits; v0.1.0 milestone needs a pinnable artifact.
      **2 min.**
- [ ] **CHANGELOG.md.** The commit log is the changelog right now.
      Cut a CHANGELOG with the v0.1.0 features section so the
      Releases page on GitHub doesn't dead-end. Use Keep-a-Changelog
      style. **~20 min.**

## Tier 2 — observability + DX polish

Round out the work landed in commit `2593da8`. None of these are
blockers for a v0.1.0 cut but they multiply the value of what's
already there.

- [ ] **Per-document deliberation counter labels.** `anchor.deliberations.*`
      currently has no document_id label — easy to add but watch
      cardinality (deliberations are infrequent, so document_id is
      probably fine). Without this you can't answer "which document
      generates the most failed deliberations."
- [ ] **JVM + Tomcat metrics enabled by default.** spring-boot-actuator
      auto-registers them but they're not exposed by default through
      `management.endpoints.web.exposure`. Adding `metrics` to the
      include list would surface JVM heap, GC, HTTP request latencies,
      DB pool stats — the standard set Grafana dashboards expect.
- [ ] **Grafana dashboard JSON checked into `docker/grafana/`.** With
      Prometheus + Jaeger already in the compose profile, a
      one-click Grafana dashboard showing the anchor.* counters as
      panels would close the "I want to see this work" loop without
      anyone having to compose queries.
- [ ] **README screenshot of a Prometheus query** showing the new
      counters in flight (deliberations_started rate, ingest duration
      histogram). Mirrors the existing Jaeger trace screenshot. The
      observability section of the README currently has no visual.
- [ ] **OTel sampling at < 1.0 in the Dockerfile default.** Currently
      `OTEL_SAMPLING_PROBABILITY=1.0` in application.yml. For prod
      should be 0.1 with a comment. Tied to the env-var docs in
      `.env.example`.

## Tier 2.5 — structural-vocabulary follow-ups (post PR #4)

PR #4 (`feat/structural-vocabulary-leak-fix`) closed the worst three
synthetic-label leaks (Chapter mislabeling, References-as-chapter,
composite citations). Live verification surfaced three second-order
leaks at the same parser / prompt boundary that the original fix
didn't reach. None are blockers; they're all paper-cuts visible in
the synthesiser's GROUNDING block's `grounded_in_sections` field.

- [ ] **`SectionDetector` synthetic "Body" fallback.** When a chapter
      has no detected sub-sections, `SectionDetector.detect` emits a
      single section literally titled `"Body"` (line 65 of
      `SectionDetector.java`). Same nature of leak as the synthetic
      `"Document"` chapter title — a load-bearing internal label that
      surfaces in user-facing output (e.g. `grounded_in_sections:
      ["Body", ...]`). Fix: rename to something the model won't quote
      verbatim (e.g. `null`, an empty string, or skip the section
      level when there's only one synthetic). **~15 min.**
- [ ] **`SectionDetector.TITLE_CASE_HEADING` regex too permissive.**
      The pattern `^([A-Z][a-zA-Z]*\s*){1,6}$` matches a line that's
      just two capital letters separated by a space — which is exactly
      what LaTeX-flattened math notation (`X_1 Y_2` → `X Y`) looks
      like after Tika strips subscripts. Result: math-notation
      residue gets misclassified as a section heading and surfaces in
      `grounded_in_sections` as `"X Y"`. Tightening the regex to
      require ≥2 words AND ≥6 chars total (or filter single-letter
      tokens) is the pragmatic stopgap. The real fix is upstream
      (Tika/PDFBox math-aware extraction) but that's a much bigger
      pull. **~10 min for the stopgap.**
- [ ] **Vocabulary instruction is suggestive, not enforcing.** The
      proposer / synthesiser prompts now say "use {structural_top}"
      but Gemma still occasionally falls back to "chapter" mid-
      paragraph despite `top_level_label="section"` in metadata. Could
      be hardened with an explicit DO/DON'T example pair in the
      prompt body, but at some point you're fighting model defaults.
      Worth a single attempt before declaring it acceptable. **~10
      min for the prompt edit + a verification run.**
- [ ] **Identity-question blind spot — author / citation metadata
      not surfaced to deliberation.** Surfaced by the
      `eval/queries-wagner.txt` sweep (Q6: *"what method does Wagner
      use to refute the conjectures in this paper?"*). Vanilla RAG
      answers correctly because PyPDF retrieves the page-0 chunk
      that bundles author + abstract + method statement; Anchor
      answers *"I do not contain any information regarding a
      researcher named Wagner"* because the summariser correctly
      filters non-claim-bearing content (author names) out of every
      summary level. The deliberation prompts thus never see the
      connection "Wagner" → "the author of this document."
      Generalises to any identity-shaped query (academic-paper
      authors, legal-opinion judges, named witnesses, narrative
      protagonists).

      Two-pronged fix, kept narrow to avoid an ETL slide:

      **Problem A — structured document metadata (this work).**
      Add an ingest-time extraction pass that fills
      `document.metadata` with two fields and only those:
      - `authors: [string]` — extracted by one LLM call against
        the front matter.
      - `citations: [{ref_num: int, raw: string}]` — captured
        from the references section the parser currently drops
        wholesale (`SectionDetector.EXCLUDED_SECTIONS`); one LLM
        call to parse the bracketed/numbered list into `[N] -> raw`
        pairs, no BibTeX-grade structure. Lets queries about cited
        authors (e.g. "what does Frankl conjecture") cross-
        reference attribution at the synthesiser layer.
      Inject both into the proposer/synthesiser prompts as
      `{document_authors}` / `{document_citations}` next to the
      existing `{document_title}` substitution. Two extra LLM calls
      per ingest. No schema migration — JSONB column already
      exists.

      **Problem B — preserve named entities in claim-bearing
      summaries (deferred, prompt-nudge only).** A single sentence
      added to chapter/section/paragraph summary prompts: "When the
      prose centrally features named people, characters, or other
      identifiable entities, preserve those names in the summary."
      Solves the narrative case (Hamlet, named witnesses) without a
      separate extraction pipeline — the summariser already has the
      prose; this changes what it filters in. Zero-cost when no such
      entities are present. Worth doing in the next ask-prompt tune
      pass; not bundled with Problem A.

      The hard line: anything beyond `authors` and `citations`
      (e.g. `primary_entities`, document-type classification, full
      BibTeX structure on citations) requires a real scope
      conversation. Two fields. Two LLM calls. Stop there.

- [ ] **Parser misses inline subsection headings (`3.1.` style)
      embedded mid-chunk.** Surfaced during the eval smoke-run on
      Wagner 2019: a chunk's text begins `"3.1. Antichains of fixed
      diameter. Define the diameter..."` — `3.1. Antichains of fixed
      diameter` is a real subsection heading in the source PDF, but
      neither `ChapterDetector` nor `SectionDetector` extracted it,
      so it lives only inside the chunk text. The synthesiser then
      reads the chunk and lifts that heading into
      `grounded_in_sections` as a "title" that doesn't match any
      DB-known section row. The hallucination is *correct* (the
      heading is real in the source) but it relaxes the GROUNDING
      contract — titles are no longer strictly drawn from the
      DB-known set. Two reasonable fixes:
      (a) Improve `SectionDetector` to recognise `^\\d+\\.\\d+\\.\\s+[A-Z]`
          subsection-style headings (currently `NUMBERED_HEADING` only
          matches single-level `\\d+\\.`). Best long-term fix — it
          actually surfaces the structure that's already in the doc.
      (b) Defensive validation in `SynthesiserOutputParser`: cross-
          check GROUNDING titles against the document's known section
          + chapter titles; either drop or flag mismatches. Treats a
          symptom rather than the cause but is small.
      I'd take (a) first; (b) only if (a) doesn't fully close it.

---

## Tier 3 — protocol + multi-tenancy hardening

The deliberate-v0-scope items. These move Anchor from "single-tenant
local tool" toward "service that can host multiple users." Each is
its own design conversation.

- [ ] **Per-user auth.** Today: single shared bearer. Future: API keys
      per user with scopes (read / write / admin). JWT + a little keys
      table; AnchorApiTokenFilter becomes a JwtAuthFilter.
- [ ] **Rate limiting.** Bucket4j or similar; per-token buckets sized
      against the LLM's actual throughput. The chat-pool slot-count
      already implicitly throttles, but a 429 surface is more honest
      than queueing forever.
- [ ] **Per-user quota tracking on tokens consumed.** TokenLedger
      already counts; need to pivot it from per-ingest-run to
      per-(user, time-window) and surface via `/usage`.
- [ ] **Multi-instance leader election for the watchdog.** Today both
      JobStore + IngestJobStore watchdogs run on every replica. Two
      replicas both running `evictExpired` is harmless (idempotent
      DELETEs) but wasteful. ShedLock with a Postgres lock would
      cut to one runner.
- [ ] **Document-level access control.** Currently any token holder
      can read every ingested document. v1: a `document_acl` table,
      filter on every read query.

## Tier 4 — distribution

The Phase 6 work (per SPEC §12). Independent of all the above.

- [ ] **Maven Central publishing for `anchor-protocol` + `anchor-client`.**
      Set up Sonatype OSSRH credentials, sign artifacts, configure
      `gradle-nexus-publish-plugin`. Tag v0.2.0.
- [ ] **PyPI publishing for `anchor-client-python`.** Already has the
      pyproject.toml; needs a CI workflow on tag-push that runs
      `python -m build && twine upload`.
- [ ] **npm publishing for `@aeyer/anchor-client`.** Same shape — CI
      workflow on tag-push that runs `npm publish` with the right
      provenance flag.
- [ ] **GitHub Releases automation.** A workflow that on `v*` tags
      cuts a release with the changelog section, attaches the bootJar,
      and triggers the publishing workflows above.

## Tier 5 — research-engineering closure

Phase 0 is the headline; these are the supporting infrastructure that
makes Phase 0 *repeatable* rather than a one-off.

- [ ] **Eval harness as code.** `docs/evaluation.md` describes the
      protocol; nothing runs it. Spec a `bin/anchor-eval` that takes
      a corpus dir + a YAML of (chunk, query, expected_role,
      expected_stance) tuples and produces an agreement matrix.
      Independent of having ground truth — the framework is the work.
- [ ] **Prompt diff harness.** Tweak the proposer/critic/synthesiser
      prompts in `anchor-server/src/main/resources/prompts/*.txt`,
      run the eval harness on a fixed corpus, diff the agreement
      matrix. Closes the SPEC §6.7 'tune until ≥80%' loop.
- [ ] **Cost / token telemetry.** TokenLedger captures per-run; route
      it into Micrometer counters too so `anchor_tokens_used_total
      {model, phase}` exists. Useful when comparing prompt variants.

---

*Generated by Claude after the cross-replica dedup + observability
landing. Updated post-PR-#4 (structural-vocabulary leak fix) with
the Tier 2.5 second-order leaks. Update or prune freely.*
