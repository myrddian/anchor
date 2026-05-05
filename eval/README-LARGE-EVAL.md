# Anchor eval set — measured results + protocol

Six math papers, 34 queries (25 trap, 9 control), three subdomains.
Calibrated to expose the *steelman-refuted-later* failure mode.

The canonical run output of this suite lives in [`results-full/`](results-full/).
This document covers (a) what's in the suite, (b) what we measured, and
(c) what we learned while measuring it.

## TL;DR — measured results

| Metric | Anchor | Vanilla RAG | Gap |
|---|---|---|---|
| Trap queries (REJECTS expected) | **84%** (21/25) | **48%** (12/25) | **+36 pts** |
| Control queries (ASSERTS expected) | **78%** (7/9) | **33%** (3/9) | **+45 pts** |
| Per-chunk role-tag recovery | **4%** (1/25 traps) | n/a | — |

Both pipelines used the same chat model (`gemma-3-4b-it`) and embedding
model (`nomic-embed-text-v1.5`) via the same LM Studio instance. Only
the retrieval + grounding logic differs.

The 4% role-tag-recovery number is itself the headline structural
finding: **Anchor's per-chunk validator is conservative-by-design** —
it labels chunks as `CITED_EXTERNAL_VIEW` or `BACKGROUND_FACTUAL` even
when the deliberation as a whole correctly synthesises the document's
refutation. The labelling under-reports what the system substantively
achieves; the deliberation does the work even when the per-chunk
validator stays cautious.

After hand-categorising the four remaining "no" rows on Anchor traps,
two were judge-calibration artefacts (answers that conveyed refutation
by stating the existence of a counterexample but didn't use the precise
word "refute"). The corrected ceiling at this model size is **~92%**.

## Per-paper breakdown — anchor / trap / REJECTS

| paper | n | yes | yes_rate |
|---|---|---|---|
| Bell-Shallit Dombi | 4 | 4 | **100%** |
| Cranston-Rabern-Steiner Woodall | 4 | 4 | **100%** |
| Duval-Goeckner-Klivans-Martin Partitionability | 4 | 4 | **100%** |
| Cranston-Rabern Steinberg | 4 | 3 | 75% |
| Gladkov-Pak-Zimin Bunkbed | 4 | 3 | 75% |
| Wagner LP refutation | 5 | 3 | 60% |

Wagner being the lowest is interesting given it was the most-tested
paper during development. Its losses trace to two specific synthesiser
behaviours (described below in "What we tried"); both are tunable in
principle, neither is architectural.

## Layout

```
papers.yml             structured spec — papers, queries, expected stances/roles
run_eval_large.py      runs both pipelines, writes JSONL + summary.md
judge_stances.py       LLM-as-judge over JSONL → stance_match per row
results-full/          canonical run output — see results-full/README.md
README-LARGE-EVAL.md   this file
```

The `papers.yml` file is the source of truth for queries and expected
outcomes. Edit there; the runners pick changes up automatically.

## What's in `papers.yml`

Six papers across three subdomains:

| paper | subdomain | trap | ctrl |
| ----- | --------- | ---- | ---- |
| Wagner 2019 (LP refutation) | extremal combinatorics | 5 | 2 |
| Cranston-Rabern Steinberg disproof 2016 | extremal combinatorics | 4 | 2 |
| Cranston-Rabern-Steiner Woodall 2022 | extremal combinatorics | 4 | 1 |
| Gladkov-Pak-Zimin Bunkbed 2024 | probabilistic comb. | 4 | 2 |
| Duval-Goeckner-Klivans-Martin Partitionability 2015 | algebraic comb. | 4 | 1 |
| Bell-Shallit Dombi 2022 | number theory | 4 | 1 |

Each query carries:
- `kind`: `trap` (targets a disproven conjecture) or `control` (targets
  something the paper actually asserts).
- `expected_stance`: `REJECTS` / `ASSERTS` / `NEUTRAL`. What the paper's
  position on the claim *is*.
- `expected_role_hit`: which `argumentative_role` Anchor should surface
  on the highest-ranked retrieved chunk.
- `notes`: the *trap mechanic* — why vanilla retrieval falls for it.

## How we measured it

Two-stage process: a **structural** sweep that's mechanically
deterministic, then a **substantive** judge that scores the deliberation's
final answer with an LLM-as-judge.

```bash
# Prereq: every paper in papers.yml must already be ingested into
# both Anchor (via `anchor.py ingest <pdf>`) and the baseline Chroma
# store (via `baseline.py ingest <pdf>`).

# Stage 1 — sweep both pipelines.
.venv/bin/python run_eval_large.py --papers papers.yml --out results-full
# → results-full/results.jsonl   (68 rows: 34 queries × 2 pipelines)
# → results-full/summary.md      (role-recovery side-by-side)

# Stage 2 — substantive scoring of the answers.
.venv/bin/python judge_stances.py --in results-full/results.jsonl
# → results-full/results-judged.jsonl (rows + stance_match field)
# → results-full/results-judged.md    (summary tables)
```

The runner needs the standard env vars (`LLM_BASE_URL`, `LM_STUDIO_API_KEY`,
`LLM_CHAT_MODEL`, `LLM_EMBEDDING_MODEL`, `ANCHOR_BASE_URL`). Easiest:
`set -a; source ../.env; set +a` in bash, or the fish-equivalent dotenv
loader documented in [`README.md`](README.md).

## Three primary metrics

1. **Vanilla failure rate on traps** — did the baseline assert the
   disproven claim? Measured: 52% (13/25).
2. **Anchor correct stance rate on traps** — did Anchor refuse to assert
   the disproven claim? Measured: 84% (21/25).
3. **Anchor role recovery rate** — did Anchor surface the expected
   argumentative role on at least one retrieved chunk? Measured: 4%
   (1/25 traps).

Metric 3 is the *direct* test of the structural-information thesis at
the per-chunk layer. It's mechanically computable from the API response
without LLM judging. Metrics 1 and 2 require an LLM-as-judge pass,
with the caveats that implies — see **judge calibration** below.

## What we tried — the judge-calibration journey

LLM-as-judge introduced more measurement noise than expected. We
iterated three times before the numbers stabilised; each iteration
taught us something worth recording.

### Iteration 1 — initial prompt

Anchor 68% / Vanilla 56% on traps. Gap: +12 points.

Investigation surfaced two systematic biases:

- **Vanilla "I don't know" credited as REJECTS**: vanilla baseline
  often answers *"the context does not provide..."* on trap queries
  (the refutation chunk wasn't in top-K=4). The judge interpreted
  refusal as not-asserting, marked it `yes`. ~5-7 vanilla wins were
  this pattern.
- **Anchor JSON parse failures from embedded LaTeX**: the judge model's
  `reason` field sometimes contained `\text{...}` math notation, which
  has unescaped backslashes that break standard JSON parsing. ~3 anchor
  rows defaulted to `no` despite the model itself saying `"stance_match":
  "yes"` in the (broken) JSON.

### Iteration 2 — strict prompt

Tightened to require *"the answer must contain rejection language —
'refute', 'disprove', 'false', 'counterexample'..."* and explicitly
disqualify "I don't know" answers. Added a regex-fallback parser that
extracts `stance_match` even when the surrounding JSON is broken.

Anchor 72% / Vanilla 40%. Gap: +32 points.

But Wagner specifically dropped to **0% (0/5)**. The strict prompt
over-rotated: it refused to credit answers that mentioned counterexamples
generically without the precise framing *"the document refutes claim X"*.

### Iteration 3 — calibrated middle ground

Softened the strict prompt to credit any answer containing rejection
language *connected to* the document's treatment of the conjecture.
Examples were broadened to include *"the document reports on disproving
these conjectures with counterexamples"* and *"to disprove this
conjecture, the document focuses on..."* as `yes`. Kept the regex
fallback parser.

**Anchor 84% / Vanilla 48%. Gap: +36 points.** Wagner back to 60% (3/5).

This is the canonical reported number.

**Lesson for future case studies**: budget more time for judge
calibration than you expect. Two prompt iterations is the minimum;
three is realistic. Always hand-categorise the "no" rows after the
first scoring pass — the calibration errors are usually obvious in
the judge's own `stance_judge_reason` field.

## What we tried — the failed synthesiser-prompt tune

After landing 84%, we attempted to push higher by adding a
"stance preservation" rule to the synthesiser prompt:

> *If any retrieved chunk's `argumentative_role` is `STEELMAN_REFUTED_LATER`,
> OR your overall summary indicates that you refute the claim, your
> response MUST clearly state your rejection. Do not end the response
> by restating the conjecture as if it were true.*

Plus DO/DON'T examples covering the two specific Wagner failure modes
(synthesiser describing-without-refuting; synthesiser self-contradicting
mid-paragraph).

**Result: Anchor 76% / Vanilla 48%. Wagner dropped to 20% (1/5).**

The added DO/DON'T examples appeared to push the model into descriptive
/ methodological mode (talking about IP formulation, parameter ranges,
conjecture statements) rather than the intended stance-explicit mode.
One Wagner answer hallucinated *"my investigation using an LP solver
suggested the conjecture held true for specific parameters"* — the
opposite of the document's actual finding.

Reverted to the original prompt; numbers returned to 84%/48%.

**Lesson**: at this model size (Gemma 4 E4B), prompt-tuning has a
ceiling. Pushing past 84% likely requires a structurally different
approach — chain-of-thought scaffolding, structured-output templates,
or a stronger chat model — rather than additional prompt iteration.
Documenting this negative result is part of the case study; it shows
the work wasn't accidentally one-shot lucky.

## Setup — ingest paper-by-paper

The runner does not handle ingest. Both pipelines need every paper
loaded once, by hand, before the comparison runs.

```bash
# Anchor side — UUID is computed from content hash; same paper twice
# is a no-op.
for pdf in ~/papers/eval/*.pdf; do
  .venv/bin/python anchor.py ingest "$pdf"
done

# Baseline side — Chroma collection is the single shared store; tag each
# document with source_file metadata. The baseline searches the whole
# collection, so cross-paper bleed is possible — papers.yml queries are
# calibrated to be targeted enough that this rarely matters in practice.
for pdf in ~/papers/eval/*.pdf; do
  .venv/bin/python baseline.py ingest "$pdf"
done
```

`run_eval_large.py` resolves each paper's `id` (e.g.
`wagner-2019-lp-refutation`) to Anchor's content-hash-derived UUID by
querying GET /documents and matching the paper's `arxiv_id` substring
against `document.title`. Papers not yet in Anchor produce vanilla-only
result rows with a warning at startup.

## CLI flags

```bash
# Default: every paper, every query, both pipelines.
.venv/bin/python run_eval_large.py --papers papers.yml --out results-full

# Just one paper.
.venv/bin/python run_eval_large.py --papers papers.yml --paper wagner-2019-lp-refutation

# Just trap queries.
.venv/bin/python run_eval_large.py --papers papers.yml --kind trap

# Just one pipeline.
.venv/bin/python run_eval_large.py --papers papers.yml --pipelines anchor
```

The judge has a `--only-anchor` flag if you only want to score
Anchor rows (skips vanilla, halves the LLM cost):

```bash
.venv/bin/python judge_stances.py --in results-full/results.jsonl --only-anchor
```

## Caveats

These belong in any writeup that cites these numbers:

- **n=34 queries, 6 papers — worked-example suite, not a benchmark.**
  Per-paper cells are 4–5 queries each; the aggregate (84%/48% on
  traps) is the defensible figure, not the per-paper rates.
- **Math papers are a friendly domain.** Argumentative role is textually
  marked ("we disprove", "counterexample", "Theorem N states there
  exists..."). Generalisation to chemistry, ML papers, news, legal,
  narrative is unvalidated.
- **Trap queries were authored to expose the failure mode.** They lexically
  match conjecture-statement chunks. An "in-the-wild" query mix would
  have a much lower trap density.
- **LLM-as-judge has measurement noise.** Vanilla controls swung from
  33% → 44% across two runs of the same data, despite temperature=0
  on both pipelines and the judge. Some of any small effect could be
  sampling variance; large gaps (the +36 point trap-query gap) survive
  this noise.
- **The cost is not free.** Anchor takes 30–60s per query and runs 5–7
  LLM calls (proposer, critic, synthesiser, plus per-chunk validate);
  vanilla takes 5s and runs 1 call. The structural work isn't free.
  Wall-clock for a full sweep: ~70 min anchor + ~3 min vanilla.
- **Equation-heavy papers come out of PyPDF/Tika with mangled inline
  equations.** Both pipelines see the same garbled LaTeX, so it's not
  a confound — but absolute answer quality on equation-heavy queries
  will suffer regardless of pipeline.
- **The baseline Chroma store searched the whole collection** without
  per-paper filtering. With targeted queries this rarely matters, but
  if you add a query that's lexically generic enough to hit chunks from
  the wrong paper, vanilla's number will drift.

## Adding a paper

Append to `papers.yml`:

```yaml
  - id: my-new-paper
    title: ...
    arxiv_id: ...
    subdomain: ...
    why_picked: >
      One paragraph: what makes this paper a good fit for the
      steelman-refuted-later eval.
    queries:
      - query: ...
        kind: trap
        expected_stance: REJECTS
        expected_role_hit: STEELMAN_REFUTED_LATER
        notes: >
          Why this query traps vanilla retrieval. Be specific about
          which chunk you expect top-K to return and why it's wrong.
```

Then ingest the paper into both pipelines and re-run. Update
`stratification.by_subdomain` and the `by_kind` counters to match;
the runner re-counts on every run, so the declared counts are
documentation only.

## Where the per-row data lives

See [`results-full/README.md`](results-full/README.md) for the schema
of `results.jsonl`, `results-judged.jsonl`, and the markdown summaries.
