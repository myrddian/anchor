# test-corpus/

Local-only directory for chemistry papers used in eval. **Contents are
gitignored** (see the project root `.gitignore`); only this README is
tracked.

## Why gitignored

Most chemistry papers are not redistributable under Anchor's licence. Storing
them in the repo would violate publisher terms. Each developer/evaluator
populates their own `test-corpus/` from their personal access (institutional
subscriptions, author preprints, OA papers).

## Expected layout

```
test-corpus/
├── README.md                               (this file)
├── <author><year>-<short-title>.pdf
└── evaluations/
    └── <author><year>-<short-title>.json   (PaperEvaluation record)
```

## Selection criteria

- ~10 papers total for the v0 eval set.
- At least **3 with steelman-then-refute structure** — a chunk early in the
  paper presents a hypothesis that the discussion section later dismantles.
  These are the load-bearing eval cases for `/validate` and `/ask`.
- Prefer papers with clear section structure (Abstract / Introduction /
  Methods / Results / Discussion / Conclusion). Heavy use of equations or
  multi-column figures may stress PDFBox; SPEC §13 lists this as an accepted
  v0 risk.

## PaperEvaluation record (forthcoming)

Each paper gets a sibling JSON record under `evaluations/` with:

- Expected `document_stance_on_query` for a small set of queries.
- Chunk-level annotations: which chunks the chemist considers load-bearing or
  refuted-later.
- Steelman markers: which paragraph/section presents the steelman, which
  section refutes it.

Schema lands when Phase 2 begins.

## See also

- [docs/evaluation.md](../docs/evaluation.md) — eyeball protocol and success
  criteria.
- [SPEC.md §1.4 / §8.4](../SPEC.md) — success criteria and end-to-end testing.
