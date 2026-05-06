<!--
Thanks for the PR! Anchor is in v0 — small, focused PRs against a
single concern land fastest. If this is a bigger change, please open
an issue first to discuss the shape.
-->

## What this changes

<!--
One short paragraph: what's different after this PR, in user-visible
terms. If there's a linked issue, "Closes #N" here is fine.
-->

## Why

<!--
The problem this solves, or the gap it closes. Reference SPEC.md or
docs/follow-ups.md sections if relevant — keeping the why traceable
matters more than completeness in this section.
-->

## How

<!--
Brief technical sketch of the approach. Highlight any non-obvious
trade-offs, things you considered and rejected, or load-bearing
assumptions a reviewer should challenge.
-->

## Test plan

<!--
Concrete checks you ran. Tick what applies and add specifics where
useful.
-->

- [ ] `./gradlew test` passes locally
- [ ] Manual / live test (describe below if checked)
- [ ] No schema changes, OR a new Flyway migration is included and
      named per convention (`V<n>__<description>.sql`)
- [ ] Public API / SDK surface unchanged, OR the change is documented
      and reflected in the relevant SDK(s)
- [ ] No regression in `docs/follow-ups.md` items already marked done

<!-- Manual test details, screenshots, before/after traces, etc. -->

## Scope check

<!--
Anchor's v0 scope is deliberately narrow. Tick whichever applies,
add a one-line note if "expands scope".
-->

- [ ] Stays inside the v0 surface defined in [SPEC.md](../SPEC.md)
- [ ] Implements an item from [docs/follow-ups.md](../docs/follow-ups.md) (which one?)
- [ ] Expands scope (justified below)

## Risk

<!--
- For ingest / persistence changes: what's the migration path for
  documents already in DB?
- For prompt changes: did you re-run the eval suite (or relevant
  subset) and observe any deltas?
- For client / SDK changes: which versions are impacted?
- For deployment changes: does the existing docker-compose flow still
  work end-to-end?
-->

---

<!--
Security-sensitive changes should be coordinated through a private
security advisory before opening a public PR — see SECURITY.md.
-->
