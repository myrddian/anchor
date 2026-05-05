"""
Anchor-side runner for the same query set the vanilla-RAG baseline runs against.

Mirrors `baseline.py`'s CLI surface (`ingest` / `ask` / `compare`) so the two
output JSONLs can be paired by line index for the case-study side-by-side. The
salient difference is what each line *contains*: vanilla RAG dumps a flat
answer + retrieved chunks, Anchor dumps the deliberation transcript + the
synthesiser's GROUNDING block + (optionally) per-chunk validate enums.

Same LLM + embedding model under the hood (LM Studio is shared); the only
moving parts are retrieval shape and grounding semantics.

Usage:
    pip install -r requirements.txt
    export ANCHOR_BASE_URL=http://localhost:8090

    # 1. Ingest the same paper as baseline (uploads + waits for COMPLETED).
    python anchor.py ingest ~/papers/wagner-1903.05495.pdf

    # 2. Single query.
    python anchor.py ask "what is the maximum diversity ..."

    # 3. Full sweep -> JSONL.
    python anchor.py compare queries-wagner.txt
    python anchor.py compare queries-wagner.txt --validate-chunks

The state file (`./anchor-eval-state.json`) records the document_id of the
most recent ingest so subsequent `ask` / `compare` calls find it without
re-uploading. Delete it to force a fresh ingest.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

BASE_URL = os.environ.get("ANCHOR_BASE_URL", "http://localhost:8090").rstrip("/")
API_TOKEN = os.environ.get("ANCHOR_API_TOKEN", "")
TOP_K = 4  # match baseline.py default

STATE_FILE = Path(os.environ.get("ANCHOR_EVAL_STATE", "./anchor-eval-state.json"))

INGEST_TIMEOUT_S = 1800  # ingestion summarises every chunk; large papers run minutes
ASK_TIMEOUT_S = 600  # single deliberation


def _headers() -> dict:
    h = {"Content-Type": "application/json"}
    if API_TOKEN:
        h["Authorization"] = f"Bearer {API_TOKEN}"
    return h


def _post(path: str, body: dict) -> dict:
    r = requests.post(f"{BASE_URL}{path}", headers=_headers(), json=body, timeout=60)
    r.raise_for_status()
    return r.json()


def _get(path: str) -> dict:
    h = {k: v for k, v in _headers().items() if k != "Content-Type"}
    r = requests.get(f"{BASE_URL}{path}", headers=h, timeout=60)
    r.raise_for_status()
    return r.json()


def _read_state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {}


def _write_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, indent=2))


def _wait_terminal(get_url: str, timeout_s: int, terminal: tuple) -> dict:
    """Poll an endpoint that returns {'status': ...} until status hits a
    terminal value or the timeout expires. Returns the final response body."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        body = _get(get_url)
        if body.get("status") in terminal:
            return body
        time.sleep(2)
    raise TimeoutError(f"{get_url} did not reach {terminal} within {timeout_s}s")


def cmd_ingest(pdf_path: str) -> None:
    path = Path(pdf_path).expanduser().resolve()
    if not path.exists():
        sys.exit(f"file not found: {path}")

    print(f"uploading {path.name} -> {BASE_URL}/ingest/upload")
    h = {k: v for k, v in _headers().items() if k != "Content-Type"}
    with path.open("rb") as fp:
        r = requests.post(
            f"{BASE_URL}/ingest/upload",
            headers=h,
            files={"file": (path.name, fp, "application/pdf")},
            timeout=120,
        )
    r.raise_for_status()
    accepted = r.json()
    job_id = accepted["job_id"]
    print(f"  ingest_job_id={job_id}; polling progress...")

    last_pct = -1
    deadline = time.time() + INGEST_TIMEOUT_S
    job = {}
    while time.time() < deadline:
        job = _get(f"/ingest/jobs/{job_id}")
        pct = job.get("percent_complete", 0)
        if pct != last_pct:
            phase = job.get("phase", "?")
            msg = job.get("message", "")
            print(f"  [{pct:3d}%] {phase} - {msg}")
            last_pct = pct
        if job.get("status") in ("COMPLETED", "FAILED", "CANCELLED"):
            break
        time.sleep(2)

    if job.get("status") != "COMPLETED":
        sys.exit(f"ingest ended in {job.get('status')}: {json.dumps(job, indent=2)}")

    result = job.get("result", {}) or {}
    doc_id = result.get("document_id") or job.get("document_id")
    print(f"  ingested document_id={doc_id}")
    print(json.dumps(
        {k: result.get(k) for k in
         ("title", "chapter_count", "section_count",
          "paragraph_count", "chunk_count", "token_usage")},
        indent=2))
    _write_state({"document_id": doc_id, "title": result.get("title"),
                  "source_path": str(path), "ingested_at": result.get("ingested_at")})


def _retrieve(question: str, doc_id: str, k: int = TOP_K) -> list:
    body = _post("/retrieve", {"query": question, "document_id": doc_id, "k": k})
    return body.get("hits", [])


def _validate_chunk(chunk_id: str, question: str) -> dict:
    return _post("/validate", {"chunk_id": chunk_id, "query": question})


def _ask_full(question: str, doc_id: str) -> dict:
    accepted = _post(f"/documents/{doc_id}/ask", {"query": question})
    job_id = accepted["job_id"]
    return _wait_terminal(f"/jobs/{job_id}", ASK_TIMEOUT_S,
                          ("COMPLETED", "FAILED", "CANCELLED"))


def cmd_ask(question: str, *, validate_chunks: bool = False, verbose: bool = True) -> dict:
    state = _read_state()
    doc_id = state.get("document_id")
    if not doc_id:
        sys.exit("no document ingested; run `anchor.py ingest <pdf>` first")

    t0 = time.time()
    job = _ask_full(question, doc_id)
    ask_ms = int((time.time() - t0) * 1000)

    synth = job.get("synthesiser") or {}
    proposer = job.get("proposer") or {}
    critic = job.get("critic") or {}

    result = {
        "question": question,
        "anchor_status": job.get("status"),
        "final_response": job.get("final_response"),
        "grounding": synth.get("grounding"),
        "proposer_response": proposer.get("response"),
        "critic_challenges": critic.get("challenges"),
        "incorporated_critic_challenges":
            (synth.get("grounding") or {}).get("incorporated_critic_challenges"),
        "rejected_critic_challenges":
            (synth.get("grounding") or {}).get("rejected_critic_challenges"),
        "timings_ms": {"ask": ask_ms},
    }

    if validate_chunks:
        t0 = time.time()
        hits = _retrieve(question, doc_id, k=TOP_K)
        per_chunk = []
        for h in hits:
            v = _validate_chunk(h["chunk_id"], question)
            per_chunk.append({
                "chunk_id": h["chunk_id"],
                "section_title": h.get("section_title"),
                "section_synthetic": h.get("section_synthetic", False),
                "chapter_title": h.get("chapter_title"),
                "chapter_synthetic": h.get("chapter_synthetic", False),
                "score": h.get("score"),
                "argumentative_role": v.get("argumentative_role"),
                "document_stance_on_query": v.get("document_stance_on_query"),
                "is_load_bearing": v.get("is_load_bearing"),
                "qualifying_context": v.get("qualifying_context"),
                "preview": (h.get("text") or "")[:240].replace("\n", " "),
            })
        result["validate_per_chunk"] = per_chunk
        result["timings_ms"]["validate_per_chunk"] = int((time.time() - t0) * 1000)

    if verbose:
        print(f"\nQ: {question}\n")
        print(f"A: {result['final_response']}\n")
        g = result["grounding"] or {}
        if g:
            print(f"GROUNDING.chapters: {g.get('grounded_in_chapters')}")
            print(f"GROUNDING.sections: {g.get('grounded_in_sections')}")
            print(f"GROUNDING.confidence: {g.get('confidence')}")
        if validate_chunks:
            print(f"\n--- per-chunk validate (top-{TOP_K}) ---")
            for c in result["validate_per_chunk"]:
                print(f"  role={c['argumentative_role']} stance={c['document_stance_on_query']} "
                      f"load_bearing={c['is_load_bearing']} | {c.get('section_title') or '(unnamed)'}")
                print(f"     {c['preview'][:160]}...")
        print(f"\nask_ms={result['timings_ms'].get('ask')}")

    return result


def cmd_compare(queries_file: str, *, validate_chunks: bool = False,
                all_documents: bool = False) -> None:
    queries = [
        line.strip()
        for line in Path(queries_file).read_text().splitlines()
        if line.strip() and not line.startswith("#")
    ]

    if not all_documents:
        # Single-doc path — uses the state-file document, writes one JSONL.
        _compare_for_state_doc(queries, validate_chunks)
        return

    # Multi-doc path — runs the same query set against every ingested
    # document. One JSONL per document so existing tooling (compare.py)
    # can render a side-by-side without changes; aggregating across papers
    # is left to the consumer (one JSONL per paper composes naturally).
    docs = _list_documents()
    if not docs:
        sys.exit("no documents ingested; run `anchor.py ingest <pdf>` first")
    print(f"Running {len(queries)} queries against {len(docs)} document(s)")
    saved_state = _read_state()
    try:
        for d in docs:
            doc_id = d["document_id"]
            title = d.get("title") or doc_id
            slug = _slug(title)
            print(f"\n>>> document: {title} ({doc_id})")
            _write_state({"document_id": doc_id, "title": title})
            out_path = Path(f"anchor-results-{slug}.jsonl")
            with out_path.open("w") as f:
                for q in queries:
                    print(f"  --- {q[:80]}")
                    try:
                        result = cmd_ask(q, validate_chunks=validate_chunks, verbose=False)
                    except Exception as e:
                        result = {"question": q, "error": str(e)}
                    # Tag with document context so the JSONL is self-contained.
                    result["document_id"] = doc_id
                    result["document_title"] = title
                    f.write(json.dumps(result) + "\n")
                    f.flush()
            print(f"  wrote {out_path}")
    finally:
        # Restore whatever state file the user had — multi-doc compare is a
        # transient operation, not a permanent state change.
        if saved_state:
            _write_state(saved_state)
    print(f"\ndone. one JSONL per document; pair each with baseline-results.jsonl via compare.py.")


def _compare_for_state_doc(queries: list, validate_chunks: bool) -> None:
    out_path = Path("anchor-results.jsonl")
    with out_path.open("w") as f:
        for q in queries:
            print(f"\n=== {q} ===")
            try:
                result = cmd_ask(q, validate_chunks=validate_chunks, verbose=False)
            except Exception as e:
                # Don't lose progress if one query times out / fails — record
                # the failure inline so compare.py can render a row for it.
                result = {"question": q, "error": str(e)}
            f.write(json.dumps(result) + "\n")
            f.flush()
            ans = (result.get("final_response") or "").replace("\n", " ")
            print(f"A: {ans[:200]}...")
    print(f"\nwrote {len(queries)} results to {out_path}")


def _list_documents() -> list:
    body = _get("/documents")
    # Server may wrap the list under a key; accept either shape.
    if isinstance(body, dict) and "documents" in body:
        return body["documents"]
    if isinstance(body, list):
        return body
    return []


def _slug(title: str) -> str:
    # Filesystem-safe slug for the per-doc JSONL filename. Lowercase, only
    # [a-z0-9-], collapse runs of separators.
    import re
    s = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return s or "untitled"


def main() -> None:
    p = argparse.ArgumentParser(description="Anchor-side runner for eval comparison.")
    sub = p.add_subparsers(dest="cmd", required=True)

    p_ingest = sub.add_parser("ingest", help="Upload a PDF, wait for ingest, save document_id.")
    p_ingest.add_argument("pdf")

    p_ask = sub.add_parser("ask", help="Ask one question against the ingested document.")
    p_ask.add_argument("question")
    p_ask.add_argument("--validate-chunks", action="store_true",
                       help="Also call /retrieve top-k=4 + /validate per chunk (slow).")

    p_compare = sub.add_parser("compare", help="Run a query file -> JSONL.")
    p_compare.add_argument("queries_file")
    p_compare.add_argument("--validate-chunks", action="store_true",
                           help="Also dump per-chunk validate enums (slow; ~k extra LLM calls per query).")
    p_compare.add_argument("--all-documents", action="store_true",
                           help="Run the query set against every ingested document; "
                                "writes one anchor-results-<slug>.jsonl per paper.")

    args = p.parse_args()
    if args.cmd == "ingest":
        cmd_ingest(args.pdf)
    elif args.cmd == "ask":
        cmd_ask(args.question, validate_chunks=args.validate_chunks)
    elif args.cmd == "compare":
        cmd_compare(args.queries_file,
                    validate_chunks=args.validate_chunks,
                    all_documents=args.all_documents)


if __name__ == "__main__":
    main()
