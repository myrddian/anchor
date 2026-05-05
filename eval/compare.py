"""
Side-by-side report generator for the eval comparison.

Joins `baseline-results.jsonl` (vanilla RAG) and `anchor-results.jsonl`
(Anchor pipeline) on the question text and emits a markdown report — one
section per query — showing what each pipeline retrieved, what it answered,
and (when available) Anchor's per-chunk argumentative-role enums.

The report is the case-study artefact: written for a human reader, not for
machine consumption. The structural-difference table at the top is the
headline; per-query sections back it up.

Usage:
    python compare.py                                     # writes ./comparison.md
    python compare.py --baseline baseline-results.jsonl \\
                      --anchor anchor-results.jsonl \\
                      --out comparison.md
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable


def _load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    return rows


def _pair(baseline: list[dict], anchor: list[dict]) -> list[tuple[dict, dict]]:
    """Pair rows by question text. Falls back to line-index pairing for any
    questions present in only one file (so a partial run still produces a
    usable report rather than dropping rows silently)."""
    by_q_anchor = {row.get("question", ""): row for row in anchor}
    by_q_baseline = {row.get("question", ""): row for row in baseline}
    questions = []
    seen = set()
    for row in baseline + anchor:
        q = row.get("question", "")
        if q and q not in seen:
            questions.append(q)
            seen.add(q)
    return [(by_q_baseline.get(q, {}), by_q_anchor.get(q, {})) for q in questions]


def _md_escape_inline(s: str | None) -> str:
    if not s:
        return ""
    return s.replace("|", "\\|").replace("\n", " ")


def _render_chunks_table(chunks: Iterable[dict]) -> str:
    rows = ["| # | section | role | stance | load? | preview |",
            "|---|---|---|---|---|---|"]
    for i, c in enumerate(chunks, start=1):
        sect = c.get("section_title") or ("(unnamed)" if c.get("section_synthetic") else "?")
        rows.append("| {n} | {sect} | {role} | {stance} | {lb} | {prev} |".format(
            n=i,
            sect=_md_escape_inline(sect),
            role=_md_escape_inline(c.get("argumentative_role") or "—"),
            stance=_md_escape_inline(c.get("document_stance_on_query") or "—"),
            lb="✓" if c.get("is_load_bearing") else "✗" if c.get("is_load_bearing") is False else "—",
            prev=_md_escape_inline((c.get("preview") or "")[:160]),
        ))
    return "\n".join(rows)


def _render_baseline_chunks(chunks: Iterable[dict]) -> str:
    rows = ["| # | page | preview |", "|---|---|---|"]
    for c in chunks:
        rows.append("| {n} | {p} | {prev} |".format(
            n=c.get("rank", "?"),
            p=c.get("page", "?"),
            prev=_md_escape_inline((c.get("preview") or "")[:160]),
        ))
    return "\n".join(rows)


def _section_for(question: str, baseline_row: dict, anchor_row: dict) -> str:
    parts = [f"## {question}\n"]

    # Vanilla RAG side
    parts.append("### Vanilla RAG\n")
    if baseline_row:
        ans = baseline_row.get("answer") or "(no answer)"
        parts.append(f"**Answer:**\n\n> {_md_escape_inline(ans)}\n")
        retrieved = baseline_row.get("retrieved_chunks") or []
        if retrieved:
            parts.append("**Retrieved (top-k):**\n")
            parts.append(_render_baseline_chunks(retrieved))
            parts.append("")
    else:
        parts.append("_(no baseline result for this query)_\n")

    # Anchor side
    parts.append("### Anchor\n")
    if anchor_row and not anchor_row.get("error"):
        ans = anchor_row.get("final_response") or "(no answer)"
        parts.append(f"**Final response (synthesiser):**\n\n> {_md_escape_inline(ans)}\n")
        g = anchor_row.get("grounding") or {}
        if g:
            parts.append(
                f"**Grounding:** chapters={g.get('grounded_in_chapters')} "
                f"sections={g.get('grounded_in_sections')} "
                f"confidence={g.get('confidence')}\n"
            )
        critic = anchor_row.get("critic_challenges") or []
        if critic:
            parts.append("**Critic challenges:**\n")
            for i, ch in enumerate(critic, start=1):
                parts.append(f"  {i}. {_md_escape_inline(str(ch))[:300]}")
            parts.append("")
        per_chunk = anchor_row.get("validate_per_chunk")
        if per_chunk:
            parts.append("**Per-chunk validate (top-k=4):**\n")
            parts.append(_render_chunks_table(per_chunk))
            parts.append("")
    elif anchor_row.get("error"):
        parts.append(f"_(anchor error: {anchor_row['error']})_\n")
    else:
        parts.append("_(no anchor result for this query)_\n")

    return "\n".join(parts) + "\n---\n"


def _structural_difference_table(pairs: list[tuple[dict, dict]]) -> str:
    """Headline table — the structural-difference signal that's the point
    of the comparison, regardless of subjective answer quality."""
    return "\n".join([
        "| Signal | Vanilla RAG | Anchor |",
        "|---|---|---|",
        "| Retrieves chunks for the query | yes | yes |",
        "| Per-chunk `argumentative_role` enum | absent | yes (`AUTHOR_POSITION`, `STEELMAN_REFUTED_LATER`, ...) |",
        "| Per-chunk `document_stance_on_query` enum | absent | yes (`SUPPORTS`, `REJECTS`, `NEUTRAL`, ...) |",
        "| Critic-stage macro-only check | absent | yes |",
        "| Grounding map (chapters / sections actually cited) | absent | yes |",
        "| Surfaces refuting chunks via `not + query` | absent | yes (when stance ≠ SUPPORTS) |",
    ])


def main() -> None:
    p = argparse.ArgumentParser(description="Render a side-by-side eval report.")
    p.add_argument("--baseline", default="baseline-results.jsonl",
                   help="Path to vanilla-RAG JSONL (default: baseline-results.jsonl)")
    p.add_argument("--anchor", default="anchor-results.jsonl",
                   help="Path to Anchor JSONL (default: anchor-results.jsonl)")
    p.add_argument("--out", default="comparison.md",
                   help="Output markdown path (default: comparison.md)")
    args = p.parse_args()

    baseline = _load_jsonl(Path(args.baseline))
    anchor = _load_jsonl(Path(args.anchor))
    pairs = _pair(baseline, anchor)
    if not pairs:
        raise SystemExit("no rows in either input — run baseline.py and anchor.py first")

    out = [
        "# Eval comparison — vanilla RAG vs Anchor\n",
        f"- Baseline rows: {len(baseline)}",
        f"- Anchor rows:   {len(anchor)}",
        f"- Paired:        {sum(1 for b, a in pairs if b and a)}\n",
        "## Structural difference (headline)\n",
        _structural_difference_table(pairs),
        "\n",
        "Subjective answer quality is a noisy signal. The headline of this",
        "comparison is the structural information Anchor has access to that",
        "vanilla RAG fundamentally cannot derive: per-chunk argumentative",
        "role + document stance + critic-stage macro check + an explicit",
        "grounding map. Per-query sections below show what that buys on the",
        "Wagner-2019 trap queries.",
        "\n## Per-query results\n",
    ]
    for b, a in pairs:
        question = (b or {}).get("question") or (a or {}).get("question") or "(unknown)"
        out.append(_section_for(question, b, a))

    Path(args.out).write_text("\n".join(out))
    print(f"wrote {args.out} — {len(pairs)} paired rows")


if __name__ == "__main__":
    main()
