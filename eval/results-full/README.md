# Results — canonical run

Output of the 6-paper, 34-query eval suite, scored by LLM-as-judge.
This is the data the case study cites. See
[`../README-LARGE-EVAL.md`](../README-LARGE-EVAL.md) for context,
methodology, and caveats.

## Files

| file | what it is |
|---|---|
| `results.jsonl` | One row per (paper, query, pipeline). 68 rows = 34 queries × 2 pipelines. Raw output of `run_eval_large.py`. |
| `summary.md` | Side-by-side markdown table per (paper, query). Includes the role-recovery column (`role_recovered = expected_role_hit ∈ role_tags_seen`). |
| `results-judged.jsonl` | Same rows as `results.jsonl` plus `stance_match` (`yes` / `no` / `partial`) and `stance_judge_reason` fields. Output of `judge_stances.py`. |
| `results-judged.md` | Aggregated summary tables — yes/no/partial counts per (pipeline, kind, expected_stance), plus per-paper anchor trap-rejection rates. |
| `README.md` | This file. |

## Reading `results.jsonl`

Each row matches the `QueryResult` dataclass in `run_eval_large.py`:

```jsonc
{
  "paper_id": "wagner-2019-lp-refutation",
  "arxiv_id": "1903.05495",
  "query": "What is the maximum diversity of an intersecting set system, according to Frankl and Huang?",
  "kind": "trap",                              // "trap" | "control"
  "expected_stance": "REJECTS",                // "REJECTS" | "ASSERTS" | "NEUTRAL"
  "expected_role_hit": "STEELMAN_REFUTED_LATER",  // expected argumentative_role on at least one retrieved chunk
  "pipeline": "anchor",                        // "anchor" | "vanilla"
  "answer": "...",                             // the pipeline's final answer text
  "retrieved_chunks": [...],                   // per-chunk metadata (varies by pipeline)
  "role_tags_seen": ["CITED_EXTERNAL_VIEW", "BACKGROUND_FACTUAL", ...],  // anchor only
  "stance_tags_seen": ["NEUTRAL", "REJECTS", ...],                         // anchor only
  "grounding": {                               // anchor only — synthesiser's GROUNDING block
    "grounded_in_chapters": ["3. Main results"],
    "grounded_in_sections": ["3.1. Antichains of fixed diameter."],
    "confidence": "high",
    ...
  },
  "critic_challenges": [...],                  // anchor only — critic-stage objections
  "timings_ms": {                              // wall time per stage
    "retrieve": 124, "validate_per_chunk": 8421, "ask": 47200
  },
  "error": null                                // populated on failure
}
```

Vanilla rows have a simpler shape (no `role_tags_seen`, no `grounding`,
no `critic_challenges` — the baseline pipeline doesn't produce these
signals).

## Reading `results-judged.jsonl`

Same as `results.jsonl` plus two fields per row that has an `answer`:

```jsonc
{
  ...,
  "stance_match": "yes",                       // "yes" | "no" | "partial"
  "stance_judge_reason": "The answer correctly notes that..."
}
```

`stance_match` is the substantive correctness verdict from the LLM
judge — does the pipeline's answer correctly convey the document's
actual stance on the query?

`stance_judge_reason` records the judge's 1-2 sentence rationale.
Useful when categorising "no" rows to distinguish real failures from
judge-calibration artefacts (~6% of "no" rows on this corpus, per
hand-categorisation; see the parent README).

## Headline numbers

(Reproduced from `results-judged.md` for quick scanning.)

| Pipeline | Kind | Expected | n | yes | partial | no | yes_rate |
|---|---|---|---|---|---|---|---|
| anchor | trap | REJECTS | 25 | 21 | 0 | 4 | **84%** |
| anchor | control | ASSERTS | 9 | 7 | 0 | 2 | **78%** |
| vanilla | trap | REJECTS | 25 | 12 | 0 | 13 | 48% |
| vanilla | control | ASSERTS | 9 | 3 | 0 | 6 | 33% |

Anchor's per-chunk role-tag recovery rate (independent of substantive
correctness): **4%** (1/25 trap rows) — see `summary.md` for the per-row
breakdown.

## Reproducing this run

From `eval/`:

```bash
set -a && source ../.env && set +a   # bash; for fish see ../README.md
.venv/bin/python run_eval_large.py --papers papers.yml --out results-full
.venv/bin/python judge_stances.py --in results-full/results.jsonl
```

Sweep takes ~70 min wall-clock (Anchor is the bottleneck — 5–7 LLM
calls per query × 34 queries). Judge takes ~3-5 min (one LLM call per
row, 68 rows).

Expect minor row-level variance run-to-run — both pipelines and the
judge use temperature=0, but LM Studio batching introduces small
non-determinism. The aggregate numbers (especially the +36 point trap
gap) are robust to this noise; small per-paper movements may not be.
