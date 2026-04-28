# Evaluation

> Placeholder. The v0 evaluation strategy is described in
> [SPEC.md](../SPEC.md) §1.4 (success criteria) and §8 (testing strategy).

## Corpus

A curated set of ~10 chemistry papers in `test-corpus/` (gitignored — papers
are not redistributable). At least three of the papers should exhibit
**steelman-then-refute** structure, where a chunk early in the paper presents
a hypothesis or claim that the discussion section later dismantles. These are
the load-bearing eval cases for `/validate` and `/ask`.

A `PaperEvaluation` record per paper notes:

- Expected `document_stance_on_query` for known queries.
- Chunks the chemist has flagged as load-bearing or not.
- Steelman cases: which chunk presents the steelman, which section refutes it.

## Success criteria (SPEC §1.4)

- Ingest under 5 minutes per paper on the inference node.
- `/validate` under 15 seconds for typical chunks.
- ≥80% chemist-eyeball correctness on `argumentative_role` for steelman cases.
- `/ask` deliberation under 90 seconds total on local Gemma.
- Critic catches the steelman flip on ≥2 of 3 known cases.
- Synthesiser refuses ≥2 of 3 deliberately off-topic queries with no
  fabrication.
- SSE streaming on `/ask` is visibly progressive (not a single batched dump).

## Eyeball protocol

1. Run the demo harness against each paper (SPEC §10.2).
2. Chemist rates: correct / partially correct / wrong.
3. Cap eval sessions at ~30 minutes (SPEC §13 risk: don't bottleneck on the
   chemist).
4. Track results in this document once Phase 2 lands.

## Sections to fill in

- Per-paper results table.
- Token-cost rollup per ingest and per `/ask`.
- Failure-mode catalogue (which prompt produced which class of error).
- Comparison panel: bare-chunk LLM vs. validated vs. deliberated, scored
  by the chemist.
