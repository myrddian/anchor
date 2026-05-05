"""
Eval runner for papers.yml.

Reads the structured eval set, runs every query against (a) the vanilla
baseline pipeline and (b) Anchor's HTTP API, and writes a per-query JSONL
result file plus a markdown summary table.

Assumes:
  - baseline.py (sibling) is importable; its Chroma store contains every
    paper in the eval set, ingested via `python baseline.py ingest <pdf>`.
  - Anchor is running locally with /documents, /retrieve, /validate,
    /documents/{id}/ask, /jobs/{id} endpoints. Each paper in papers.yml
    must already be ingested into Anchor (the runner does NOT handle
    ingest — do that once via `python anchor.py ingest <pdf>`).
  - Anchor and baseline use the same LM Studio (and same embedding +
    chat models) so the retrieval comparison is apples-to-apples.

The runner resolves `paper.id` (e.g. `wagner-2019-lp-refutation`) to
Anchor's content-hash-derived UUID by querying GET /documents and
matching the paper's `arxiv_id` substring against `document.title`.
Papers with no matching Anchor document get skipped with a warning;
their queries still appear in the output as `pipeline=vanilla` rows.

Usage:
  python run_eval_large.py --papers papers.yml --out results/
  python run_eval_large.py --papers papers.yml --paper wagner-2019-lp-refutation
  python run_eval_large.py --papers papers.yml --kind trap
  python run_eval_large.py --papers papers.yml --pipelines anchor
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any

import yaml
import requests

ANCHOR_BASE_URL = os.environ.get("ANCHOR_BASE_URL", "http://localhost:8090").rstrip("/")
ANCHOR_API_TOKEN = os.environ.get("ANCHOR_API_TOKEN", "")

# Retrieval depth for /retrieve before per-chunk /validate. Matches
# baseline.py's TOP_K so the comparison is apples-to-apples.
TOP_K = 4

ASK_TIMEOUT_S = 600
ASK_POLL_INTERVAL_S = 3


# ----- types ------------------------------------------------------------------


@dataclass
class QueryResult:
    paper_id: str
    arxiv_id: str
    query: str
    kind: str
    expected_stance: str
    expected_role_hit: str
    pipeline: str  # "vanilla" | "anchor"
    answer: str
    retrieved_chunks: list[dict[str, Any]] = field(default_factory=list)
    role_tags_seen: list[str] = field(default_factory=list)
    stance_tags_seen: list[str] = field(default_factory=list)
    grounding: dict[str, Any] | None = None  # anchor-only: synthesiser GROUNDING
    critic_challenges: list[str] = field(default_factory=list)
    timings_ms: dict[str, int] = field(default_factory=dict)
    error: str | None = None


# ----- HTTP helpers -----------------------------------------------------------


def _headers(content_type_json: bool = True) -> dict:
    h: dict[str, str] = {}
    if content_type_json:
        h["Content-Type"] = "application/json"
    if ANCHOR_API_TOKEN:
        h["Authorization"] = f"Bearer {ANCHOR_API_TOKEN}"
    return h


def _get(path: str) -> Any:
    r = requests.get(f"{ANCHOR_BASE_URL}{path}", headers=_headers(False), timeout=60)
    r.raise_for_status()
    return r.json()


def _post(path: str, body: dict, *, timeout: int = 60) -> Any:
    r = requests.post(f"{ANCHOR_BASE_URL}{path}", headers=_headers(),
                      json=body, timeout=timeout)
    r.raise_for_status()
    return r.json()


# ----- Anchor doc-id resolution ----------------------------------------------


def list_anchor_documents() -> list[dict[str, Any]]:
    """One call at runner-startup; cached in a module-level dict so the
    per-query loop doesn't re-hit /documents 34 times."""
    body = _get("/documents")
    if isinstance(body, dict) and "documents" in body:
        return body["documents"]
    if isinstance(body, list):
        return body
    return []


_ANCHOR_DOC_INDEX: dict[str, str] | None = None


def resolve_anchor_doc_id(paper: dict[str, Any]) -> str | None:
    """Look up Anchor's UUID for a paper by matching arxiv_id substring
    against document title. Anchor titles for arxiv ingests look like
    "1903.05495v1"; we strip the trailing version when comparing."""
    global _ANCHOR_DOC_INDEX
    if _ANCHOR_DOC_INDEX is None:
        _ANCHOR_DOC_INDEX = {}
        for d in list_anchor_documents():
            title = (d.get("title") or "").lower()
            _ANCHOR_DOC_INDEX[title] = d["document_id"]

    arxiv_id = (paper.get("arxiv_id") or "").lower()
    if not arxiv_id:
        return None
    # Match by arxiv_id substring; Anchor title may have a `vN` suffix.
    for title, doc_id in _ANCHOR_DOC_INDEX.items():
        if arxiv_id in title:
            return doc_id
    return None


# ----- pipeline adapters ------------------------------------------------------


def run_vanilla(query: str, *, paper_id: str) -> dict[str, Any]:
    """Call into baseline.py's cmd_ask. The baseline searches its single
    shared Chroma collection (no per-paper filtering), so cross-paper
    bleed is possible — `papers.yml` queries are calibrated to be
    targeted enough that this rarely matters in practice."""
    try:
        import baseline  # type: ignore
    except ModuleNotFoundError:
        return {"error": "baseline.py not importable; run from the eval/ directory with the venv active"}
    try:
        return baseline.cmd_ask(query, verbose=False)
    except Exception as e:  # noqa: BLE001
        return {"error": f"{type(e).__name__}: {e}"}


def run_anchor(query: str, *, anchor_doc_id: str) -> dict[str, Any]:
    """Three real API calls per query:
       /retrieve              — top-K chunks for this query in this doc
       /validate (per chunk)  — argumentative_role + document_stance per chunk
       /documents/{id}/ask    — full deliberation, polled to completion

    Even if /ask fails (timeout, model issue), the /validate output is the
    structural metric the case study rests on — we always return whatever
    we got, with errors noted, so the result row is informative either
    way.
    """
    out: dict[str, Any] = {"chunks": [], "role_tags": [], "stance_tags": []}

    # 1. Retrieve top-K chunks for this query, restricted to the document.
    t0 = time.time()
    try:
        retrieve_body = _post("/retrieve",
                              {"query": query, "document_id": anchor_doc_id, "k": TOP_K})
        hits = retrieve_body.get("hits", []) or []
    except Exception as e:  # noqa: BLE001
        out["retrieve_error"] = f"{type(e).__name__}: {e}"
        hits = []
    out["retrieve_ms"] = int((time.time() - t0) * 1000)

    # 2. Validate each retrieved chunk to surface argumentative_role + stance.
    t0 = time.time()
    enriched: list[dict[str, Any]] = []
    for hit in hits:
        chunk_id = hit.get("chunk_id")
        if not chunk_id:
            continue
        try:
            v = _post("/validate", {"chunk_id": chunk_id, "query": query})
        except Exception as e:  # noqa: BLE001
            v = {"error": f"{type(e).__name__}: {e}"}
        enriched.append({
            "chunk_id": chunk_id,
            "section_title": hit.get("section_title"),
            "section_synthetic": hit.get("section_synthetic", False),
            "chapter_title": hit.get("chapter_title"),
            "score": hit.get("score"),
            "argumentative_role": v.get("argumentative_role"),
            "document_stance_on_query": v.get("document_stance_on_query"),
            "is_load_bearing": v.get("is_load_bearing"),
            "qualifying_context": v.get("qualifying_context"),
            "preview": (hit.get("text") or "")[:240].replace("\n", " "),
            "validate_error": v.get("error"),
        })
    out["chunks"] = enriched
    out["validate_ms"] = int((time.time() - t0) * 1000)
    out["role_tags"] = [c["argumentative_role"] for c in enriched if c.get("argumentative_role")]
    out["stance_tags"] = [c["document_stance_on_query"] for c in enriched
                          if c.get("document_stance_on_query")]

    # 3. Full deliberation. Returns 202 + job_id; poll until terminal.
    t0 = time.time()
    try:
        accepted = _post(f"/documents/{anchor_doc_id}/ask", {"query": query})
        job_id = accepted.get("job_id")
        if not job_id:
            out["ask_error"] = "no job_id in /ask response"
        else:
            deadline = time.time() + ASK_TIMEOUT_S
            job = {}
            while time.time() < deadline:
                job = _get(f"/jobs/{job_id}")
                if job.get("status") in ("COMPLETED", "FAILED", "CANCELLED"):
                    break
                time.sleep(ASK_POLL_INTERVAL_S)
            out["ask"] = job
            if job.get("status") != "COMPLETED":
                out["ask_error"] = f"job ended in {job.get('status')}: {job.get('error')}"
    except Exception as e:  # noqa: BLE001
        out["ask_error"] = f"{type(e).__name__}: {e}"
    out["ask_ms"] = int((time.time() - t0) * 1000)

    return out


# ----- result extraction ------------------------------------------------------


def shape_vanilla_result(
    paper: dict[str, Any], query: dict[str, Any], raw: dict[str, Any]
) -> QueryResult:
    return QueryResult(
        paper_id=paper["id"],
        arxiv_id=paper["arxiv_id"],
        query=query["query"],
        kind=query["kind"],
        expected_stance=query["expected_stance"],
        expected_role_hit=query["expected_role_hit"],
        pipeline="vanilla",
        answer=raw.get("answer", ""),
        retrieved_chunks=raw.get("retrieved_chunks", []),
        role_tags_seen=[],  # vanilla has no roles, by design
        timings_ms=raw.get("timings_ms", {}),
        error=raw.get("error"),
    )


def shape_anchor_result(
    paper: dict[str, Any], query: dict[str, Any], raw: dict[str, Any]
) -> QueryResult:
    ask = raw.get("ask") or {}
    synth = ask.get("synthesiser") or {}
    critic = ask.get("critic") or {}

    answer = ask.get("final_response") or ""
    grounding = synth.get("grounding")
    challenges = critic.get("challenges") or []

    err = (raw.get("retrieve_error")
           or raw.get("validate_error")
           or raw.get("ask_error"))

    return QueryResult(
        paper_id=paper["id"],
        arxiv_id=paper["arxiv_id"],
        query=query["query"],
        kind=query["kind"],
        expected_stance=query["expected_stance"],
        expected_role_hit=query["expected_role_hit"],
        pipeline="anchor",
        answer=answer,
        retrieved_chunks=raw.get("chunks", []),
        role_tags_seen=raw.get("role_tags", []),
        stance_tags_seen=raw.get("stance_tags", []),
        grounding=grounding,
        critic_challenges=challenges,
        timings_ms={
            "retrieve": raw.get("retrieve_ms", 0),
            "validate_per_chunk": raw.get("validate_ms", 0),
            "ask": raw.get("ask_ms", 0),
        },
        error=err,
    )


# ----- summary ----------------------------------------------------------------


def render_markdown_summary(results: list[QueryResult], out_path: Path) -> None:
    """Side-by-side per (paper, query). The role-recovered column is the
    structural-metric headline: did Anchor surface the expected
    argumentative role on at least one of the retrieved chunks?"""
    by_key: dict[tuple[str, str], dict[str, QueryResult]] = {}
    for r in results:
        by_key.setdefault((r.paper_id, r.query), {})[r.pipeline] = r

    total = len(by_key)
    role_recovered_count = 0
    trap_count = 0
    trap_role_recovered = 0
    for pair in by_key.values():
        a = pair.get("anchor")
        if not a:
            continue
        if a.expected_role_hit in a.role_tags_seen:
            role_recovered_count += 1
            if a.kind == "trap":
                trap_role_recovered += 1
        if a.kind == "trap":
            trap_count += 1

    lines: list[str] = []
    lines.append("# Anchor vs vanilla RAG: per-query results\n")
    lines.append(f"- Total (paper, query) rows: {total}")
    lines.append(f"- Role recovered (any kind): {role_recovered_count} / {total}")
    lines.append(f"- Role recovered on trap queries: {trap_role_recovered} / {trap_count}\n")
    lines.append("Generated by run_eval_large.py. `role_recovered` is true iff Anchor "
                 "surfaced the expected `argumentative_role` on at least one retrieved chunk.\n")

    lines.append("| paper | kind | query | expected role | vanilla answer (truncated) | "
                 "anchor answer (truncated) | role recovered |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")

    for (paper_id, q), pair in by_key.items():
        v = pair.get("vanilla")
        a = pair.get("anchor")
        expected_role = (v or a).expected_role_hit if (v or a) else "?"
        kind = (v or a).kind if (v or a) else "?"
        recovered = (
            "yes"
            if a and expected_role in a.role_tags_seen
            else ("no" if a else "n/a")
        )
        v_ans = (v.answer if v else "(no run)").replace("\n", " ").replace("|", "\\|")[:120]
        a_ans = (a.answer if a else "(no run)").replace("\n", " ").replace("|", "\\|")[:120]
        q_short = q.replace("|", "\\|")[:80]
        lines.append(
            f"| {paper_id} | {kind} | {q_short}... | {expected_role} | "
            f"{v_ans}... | {a_ans}... | {recovered} |"
        )

    out_path.write_text("\n".join(lines) + "\n")


# ----- main -------------------------------------------------------------------


def main() -> None:
    p = argparse.ArgumentParser(description="Run the Anchor eval set.")
    p.add_argument("--papers", default="papers.yml")
    p.add_argument("--out", default="results")
    p.add_argument("--paper", help="Only run this paper id (otherwise all).")
    p.add_argument(
        "--kind",
        choices=["trap", "control"],
        help="Only run queries of this kind.",
    )
    p.add_argument(
        "--pipelines",
        default="vanilla,anchor",
        help="Comma-separated subset of pipelines to run.",
    )
    args = p.parse_args()

    pipelines = set(args.pipelines.split(","))
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    with open(args.papers) as f:
        spec = yaml.safe_load(f)

    # Pre-resolve every paper's Anchor doc-id so failures surface up front
    # rather than mid-run. Skip-on-missing with a warning so partial runs
    # still produce useful output.
    paper_id_to_anchor_id: dict[str, str | None] = {}
    if "anchor" in pipelines:
        for paper in spec["papers"]:
            doc_id = resolve_anchor_doc_id(paper)
            paper_id_to_anchor_id[paper["id"]] = doc_id
            status = doc_id if doc_id else "(NOT INGESTED — anchor queries skipped)"
            print(f"  {paper['id']}: {status}")
        missing = [k for k, v in paper_id_to_anchor_id.items() if v is None]
        if missing:
            print(f"\nNote: {len(missing)} paper(s) not in Anchor; vanilla-only rows "
                  f"will appear for them.")

    results: list[QueryResult] = []
    jsonl_path = out_dir / "results.jsonl"
    with jsonl_path.open("w") as jsonl:
        for paper in spec["papers"]:
            if args.paper and paper["id"] != args.paper:
                continue
            anchor_doc_id = paper_id_to_anchor_id.get(paper["id"])
            for q in paper["queries"]:
                if args.kind and q["kind"] != args.kind:
                    continue
                print(f"[{paper['id']}] {q['kind']}: {q['query'][:80]}...")

                if "vanilla" in pipelines:
                    raw = run_vanilla(q["query"], paper_id=paper["id"])
                    r = shape_vanilla_result(paper, q, raw)
                    results.append(r)
                    jsonl.write(json.dumps(asdict(r)) + "\n")
                    jsonl.flush()

                if "anchor" in pipelines:
                    if anchor_doc_id is None:
                        # Honest skip — no anchor row for a paper we can't reach.
                        continue
                    raw = run_anchor(q["query"], anchor_doc_id=anchor_doc_id)
                    r = shape_anchor_result(paper, q, raw)
                    results.append(r)
                    jsonl.write(json.dumps(asdict(r)) + "\n")
                    jsonl.flush()
                    if r.error:
                        print(f"  ! anchor error: {r.error}")
                    if r.role_tags_seen:
                        print(f"    roles: {r.role_tags_seen}")

    render_markdown_summary(results, out_dir / "summary.md")
    print(f"\nwrote {len(results)} results to {out_dir}/")
    print(f"  jsonl:    {jsonl_path}")
    print(f"  summary:  {out_dir / 'summary.md'}")


if __name__ == "__main__":
    main()
