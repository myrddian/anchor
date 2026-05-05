# Vanilla RAG Baseline

The foil for Anchor's case study. A deliberately unsophisticated RAG pipeline —
the obvious LangChain quickstart shape — wired up so you can run identical
queries against both pipelines, with the **same LLM and the same embedding
model**, and compare what each retrieves and answers.

The point of having a baseline isn't to make Anchor look good. The point is
to make the failure mode reproducible: *this is what every "RAG over a PDF"
tutorial on the internet ships, and here is the class of question it gets
materially wrong.*

> **Looking for the case-study results?**
> The canonical 6-paper, 34-query measurement is in
> [`README-LARGE-EVAL.md`](README-LARGE-EVAL.md) with per-row data in
> [`results-full/`](results-full/). Headline: Anchor 84% / Vanilla 48% on
> trap-rejection (+36 points), Anchor 78% / Vanilla 33% on control-assertion
> (+45 points). Per-chunk role-tag recovery 4% — itself the structural
> finding, see the linked README. The single-paper Wagner flow below is
> the quick-demo path; the multi-paper sweep is the publishable artefact.

## What this is

```
PDF -> PyPDFLoader
    -> RecursiveCharacterTextSplitter (chunk_size=1000, overlap=200)
    -> embed (nomic-embed-text-v1.5 via LM Studio, 768-dim)
    -> Chroma (local persistent store)
    -> top-k=4 similarity_search
    -> stuff into RAG prompt
    -> chat completion (gemma-3-4b-it via LM Studio)
```

Defaults are deliberate. Bumping `top_k`, swapping in an MMR retriever, adding
a reranker, or hand-tuning the prompt all close some of the gap — but none of
them solve the steelman-refutation problem, which is structural. The argument
of the case study is that *no amount of retrieval tuning* fixes a system that
treats chunks as bags of keywords; you need source-grounded validation.

## Setup

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

export LLM_BASE_URL=http://mac-studio.local:1234/v1   # or wherever LM Studio runs
export LLM_CHAT_MODEL=gemma-3-4b-it
export LLM_EMBEDDING_MODEL=nomic-embed-text-v1.5
export LM_STUDIO_API_KEY=...                          # current LM Studio rejects placeholder strings
```

Both `LLM_CHAT_MODEL` and `LLM_EMBEDDING_MODEL` must be loaded in LM Studio.
LM Studio's OpenAI-compatible server exposes them at `/v1/chat/completions`
and `/v1/embeddings` respectively.

If your shell is **fish** (no `set -a; source ../.env; set +a` equivalent),
load the project `.env` file like this:

```fish
for line in (cat ../.env | string match -rv '^\s*(#|$)')
    set -gx (string split -m 1 = -- $line)
end
```

This is a one-shot per shell session; once loaded, every subsequent
`baseline.py` / `anchor.py` / `run_eval_large.py` / `judge_stances.py`
call inherits the env automatically. Avoid running these scripts via the
system Python — the langchain stack lives in `.venv/` only; use
`.venv/bin/python ...` (or activate via `source .venv/bin/activate.fish`
on fish, `source .venv/bin/activate` on bash/zsh).

## Run

```bash
# 1. Ingest the demo paper.
python baseline.py ingest ~/papers/wagner-1903.05495.pdf

# 2. Ask one question.
python baseline.py ask "what is the maximum diversity of an intersecting set system, according to Frankl and Huang?"

# 3. Run the full query set, dump JSONL.
python baseline.py compare queries-wagner.txt
# -> baseline-results.jsonl
```

## Comparison protocol

Run the *same* `queries-wagner.txt` against Anchor's `/documents/{id}/ask`
(full deliberation) and — optionally — its `/validate` endpoint per
top-k=4 chunk, dump those to `anchor-results.jsonl`, then join both JSONLs
into a side-by-side markdown report.

```bash
# 0. Anchor server reachable (default http://localhost:8090; override
#    with ANCHOR_BASE_URL). LM Studio chat + embedding models loaded.

# 1. Ingest the same paper into Anchor.
python anchor.py ingest ~/papers/wagner-1903.05495.pdf
# -> writes anchor-eval-state.json with the resulting document_id

# 2. Same query set you ran against the baseline.
python baseline.py compare queries-wagner.txt
# -> baseline-results.jsonl (vanilla RAG: top-k=4 chunks + stuffed answer)

python anchor.py compare queries-wagner.txt
# -> anchor-results.jsonl (full deliberation transcript + grounding map)

# 3. (Optional, slow) include per-chunk argumentative-role + stance.
python anchor.py compare queries-wagner.txt --validate-chunks
# -> overwrites anchor-results.jsonl with per-chunk validate enums attached

# 4. Render the side-by-side report.
python compare.py
# -> comparison.md (paired by question text; structural-difference table
#    on top, per-query sections below)
```

### Bulk testing across multiple papers

Once you have several PDFs ingested into Anchor, you can run the same
query set against all of them in one command. One JSONL per paper, so
you can pair each one with `baseline-results.jsonl` independently:

```bash
# Ingest each paper once.
python anchor.py ingest ~/papers/wagner-1903.05495.pdf
python anchor.py ingest ~/papers/some-other-paper.pdf
python anchor.py ingest ~/papers/yet-another.pdf

# Run the same queries against every ingested document.
python anchor.py compare queries-wagner.txt --all-documents
# -> anchor-results-1903-05495v1.jsonl
# -> anchor-results-some-other-paper-title.jsonl
# -> anchor-results-yet-another-title.jsonl

# Render side-by-side per paper (rerun once per JSONL).
python compare.py --anchor anchor-results-1903-05495v1.jsonl --out cmp-wagner.md
```

The state file (`anchor-eval-state.json`) is restored to its prior
single-doc value after `--all-documents` finishes, so subsequent
`anchor.py ask "..."` calls keep targeting whichever paper you ingested
last.

The salient comparison isn't *answer quality* — that's subjective and
prompt-dependent. It's the **mechanical, reproducible** difference:

| Signal                           | Vanilla RAG | Anchor |
| -------------------------------- | ----------- | ------ |
| Retrieves chunk                  | yes         | yes    |
| `argumentative_role` on chunk    | absent      | `STEELMAN_REFUTED_LATER` |
| `document_stance_on_query`       | absent      | `REJECTS` |
| Surfaces refuting chunks         | no          | yes (via `not + query` search) |
| Critic challenges the draft      | n/a         | yes (macro-only) |
| Grounding map (chapters / sections cited) | absent | yes (verbatim titles, never sentinels) |

Any answer-layer system you build on top of vanilla RAG inherits this
blindness. Anchor gives the answer layer the structural information it
needs to do better.

## Why the defaults

- `chunk_size=1000` / `overlap=200` — straight from the LangChain
  quickstart. Changing them does not fix the argumentative-role problem.
- `top_k=4` — matches Anchor's default retrieval depth.
- `temperature=0.0` — determinism. We want the comparison to be about
  retrieval, not sampling noise.
- Same embedding + chat model as Anchor — eliminates "they used a better
  model" as a confound.

## Caveats

- LangChain version churn is severe. Pin via `requirements.txt`; do not
  `pip install -U`.
- Chroma's persist directory is local and not shared with Anchor's
  Postgres. That's fine — different stores, same papers, same questions.
- Math papers (the Wagner target) come out of PyPDF with mangled inline
  equations. Anchor has the same problem (PDFBox + Tika), so it's not a
  confound — both pipelines see the same garbled LaTeX. Pick papers
  where the prose carries the argument.
