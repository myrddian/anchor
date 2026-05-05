"""
LLM-as-judge stance scoring for results.jsonl.

The role-tag check (`expected_role_hit` ∈ `role_tags_seen`) measures whether
Anchor's per-chunk validator surfaces the *expected enum value* for a query
— a labelling correctness check, not a substantive one. A trap query can
land all four chunks at `CITED_EXTERNAL_VIEW` (technically correct: the
chunk does cite an external view) while Anchor's deliberation final answer
correctly identifies that the document refutes the conjecture. That's the
system *doing the job* with a *conservative label* — the case study
should not conflate the two.

This pass scores the substantive question:

    For each (paper, query) where expected_stance == "REJECTS",
    does the pipeline's final answer correctly convey that the document
    rejects / refutes / disproves the claim being asked about?

It runs the same chat model (LM Studio, configured via environment) as a
binary judge on each row's `answer` field. One LLM call per scored row.
Adds a `stance_match` field (`yes` | `no` | `partial`) and a
`stance_judge_reason` field, then writes the result to
`results-judged.jsonl` and prints aggregate stats.

Usage:
    python judge_stances.py --in results-full/results.jsonl
    python judge_stances.py --in results-full/results.jsonl \\
                            --out results-full/results-judged.jsonl

Defaults: input is `results-full/results.jsonl`, output is the same
directory with `-judged` suffix.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import requests

LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://localhost:1234/v1").rstrip("/")
LLM_API_KEY = os.environ.get("LM_STUDIO_API_KEY", "")
CHAT_MODEL = os.environ.get("LLM_CHAT_MODEL", "gemma-3-4b-it")

JUDGE_TEMPERATURE = 0.0
JUDGE_TIMEOUT_S = 60

JUDGE_SYSTEM = (
    "You are an impartial judge. You read a question, the expected stance of a "
    "document on that question (REJECTS / ASSERTS / NEUTRAL), and the answer "
    "produced by a system that has access to the document. You decide whether "
    "the answer correctly conveys the expected stance. You output ONLY valid "
    "JSON; no prose, no fences, no commentary."
)

JUDGE_PROMPT_TEMPLATE = """Question: {question}

The document's actual stance on this question is: {expected_stance}

For REJECTS, you are checking whether the answer conveys that the
document REFUTES / DISPROVES / PROVIDES A COUNTEREXAMPLE TO the claim.
The bar is whether rejection-language is *connected to* the document's
treatment of the conjecture being asked about — NOT whether the answer
uses one specific phrasing.

ALL of these count as "yes":
- "the document reports on disproving these conjectures with counterexamples"
- "the paper provides a counterexample to the conjecture"
- "we report an attempt to disprove Conjecture X by constructing counterexamples"
- "to disprove this conjecture, the document focuses on..."
- "the conjecture is shown to be false"
- "Theorem N states that there exists [something contradicting the conjecture]"
- The answer describes the disproof method even if it doesn't say the word "refute"

The answer does NOT have to use the exact phrase "the document refutes X" —
any clear indication that the document provides counterexamples to or
disproves the conjecture is sufficient. Verbs like "disprove",
"refute", "counterexample", or constructions stating the opposite of
the conjectured claim, all count.

The following count as "no":
- Answer asserts the conjecture as true without flagging refutation
- Answer refuses to engage ("the context does not provide",
  "the document does not state", "I cannot find this") — non-answers
  are NOT rejections; mark them "no"
- Answer describes the conjecture in detail but never mentions
  refutation, counterexamples, or the document's contrary findings

Use "partial" only when the answer is genuinely ambiguous — e.g. it
hedges so heavily ("possibly false", "may not hold") that it's unclear
whether it's reporting the document's finding.

For ASSERTS: a correct answer affirms the claim with supporting evidence.
Refusal-style answers are NOT correct for ASSERTS — mark "no".

For NEUTRAL: a correct answer presents the claim without taking a side,
or notes the document's neutrality explicitly.

Answer to judge:
\"\"\"
{answer}
\"\"\"

Output JSON matching this schema exactly. The "reason" field must use
ASCII only — no LaTeX commands, no backslash-prefixed math notation,
no unescaped backslashes. Describe formulas in words instead of writing
\\text{{...}} or similar. This is a hard requirement; LaTeX in the
reason field breaks JSON parsing downstream.

{{
  "stance_match": "yes" | "no" | "partial",
  "reason": "1-2 plain-ASCII sentences explaining the judgment"
}}"""


def _chat_complete(system_prompt: str, user_prompt: str, *, retries: int = 1) -> str:
    """Single-shot chat completion against the configured LM Studio. One
    retry on transient failure; raises on the second."""
    headers = {"Content-Type": "application/json"}
    if LLM_API_KEY:
        headers["Authorization"] = f"Bearer {LLM_API_KEY}"
    body = {
        "model": CHAT_MODEL,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": JUDGE_TEMPERATURE,
    }
    last_err: Exception | None = None
    for attempt in range(retries + 1):
        try:
            r = requests.post(f"{LLM_BASE_URL}/chat/completions", headers=headers,
                              json=body, timeout=JUDGE_TIMEOUT_S)
            r.raise_for_status()
            data = r.json()
            return data["choices"][0]["message"]["content"] or ""
        except Exception as e:  # noqa: BLE001
            last_err = e
            if attempt < retries:
                time.sleep(2)
    raise RuntimeError(f"chat completion failed after retries: {last_err}")


def _strip_fences(text: str) -> str:
    t = text.strip()
    if t.startswith("```"):
        nl = t.find("\n")
        if nl > 0:
            t = t[nl + 1:]
        if t.endswith("```"):
            t = t[:-3]
    return t.strip()


_STANCE_MATCH_PATTERN = re.compile(
    r'"stance_match"\s*:\s*"(yes|no|partial)"', re.IGNORECASE)


def _parse_judge_output(raw: str) -> dict | None:
    """Parse the model's JSON judgment, falling back to a regex extraction
    of just the stance_match value when the JSON is broken (typically by
    embedded LaTeX commands that contain unescaped backslashes — common
    when the model paraphrases mathematical content into the reason field
    despite the prompt asking it not to).

    Returns {stance_match, reason} on success, None if even the regex
    can't find a stance_match value."""
    text = _strip_fences(raw)

    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            match = parsed.get("stance_match", "")
            if match in ("yes", "no", "partial"):
                return {"stance_match": match, "reason": parsed.get("reason", "")}
    except json.JSONDecodeError:
        pass

    # Fallback: regex-extract just the stance_match value. The reason
    # field is informational; the stance_match is what the aggregation
    # actually counts. Better to capture a correct verdict with a
    # truncated reason than throw away the whole row.
    m = _STANCE_MATCH_PATTERN.search(text)
    if m:
        return {
            "stance_match": m.group(1).lower(),
            "reason": f"[fallback parse from broken JSON] raw: {text[:200]}",
        }
    return None


def judge_row(row: dict) -> dict:
    """Returns {stance_match, reason} for one scored row."""
    answer = row.get("answer") or ""
    if not answer.strip():
        return {"stance_match": "no", "reason": "empty answer"}
    prompt = JUDGE_PROMPT_TEMPLATE.format(
        question=row.get("query", ""),
        expected_stance=row.get("expected_stance", "?"),
        answer=answer.strip(),
    )
    raw = _chat_complete(JUDGE_SYSTEM, prompt)
    parsed = _parse_judge_output(raw)
    if parsed is None:
        return {"stance_match": "no", "reason": f"unparseable judge output: {raw[:200]}"}
    return parsed


def render_summary(rows: list[dict]) -> str:
    """Aggregate by (pipeline, kind, expected_stance) — the cell that
    matters most is (anchor, trap, REJECTS): how often does Anchor get
    the SUBSTANTIVE judgment right on the queries the suite was designed
    to trap?"""
    by_bucket: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    for r in rows:
        if "stance_match" not in r:
            continue
        key = (r["pipeline"], r["kind"], r["expected_stance"])
        by_bucket[key].append(r["stance_match"])

    out: list[str] = []
    out.append("# Stance-judge summary\n")
    out.append("Substantive correctness — does the pipeline's answer convey the "
               "document's actual stance? Independent of role-tag labelling.\n")
    out.append("| pipeline | kind | expected | n | yes | partial | no | yes_rate |")
    out.append("| --- | --- | --- | --- | --- | --- | --- | --- |")

    for key in sorted(by_bucket.keys()):
        pipeline, kind, expected = key
        verdicts = by_bucket[key]
        c = Counter(verdicts)
        n = len(verdicts)
        yes = c.get("yes", 0)
        partial = c.get("partial", 0)
        no = c.get("no", 0)
        rate = f"{yes / n:.0%}" if n else "—"
        out.append(f"| {pipeline} | {kind} | {expected} | {n} | {yes} | {partial} | {no} | {rate} |")

    # Per-paper anchor trap-REJECTS view — the leaderboard the case study
    # actually wants.
    out.append("\n## anchor / trap / REJECTS — per paper\n")
    out.append("| paper | n | yes | partial | no | yes_rate |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    by_paper: dict[str, list[str]] = defaultdict(list)
    for r in rows:
        if (r.get("pipeline") == "anchor" and r.get("kind") == "trap"
                and r.get("expected_stance") == "REJECTS"
                and "stance_match" in r):
            by_paper[r["paper_id"]].append(r["stance_match"])
    for paper_id in sorted(by_paper.keys()):
        verdicts = by_paper[paper_id]
        c = Counter(verdicts)
        n = len(verdicts)
        rate = f"{c.get('yes', 0) / n:.0%}" if n else "—"
        out.append(f"| {paper_id} | {n} | {c.get('yes', 0)} | {c.get('partial', 0)} | {c.get('no', 0)} | {rate} |")

    return "\n".join(out) + "\n"


def main() -> None:
    p = argparse.ArgumentParser(description="LLM-as-judge stance scoring.")
    p.add_argument("--in", dest="in_path", default="results-full/results.jsonl")
    p.add_argument("--out", dest="out_path", default=None,
                   help="Default: same dir as --in with -judged.jsonl suffix.")
    p.add_argument("--summary", default=None,
                   help="Markdown summary path. Default: alongside --out.")
    p.add_argument("--only-anchor", action="store_true",
                   help="Skip vanilla rows (default: judge both pipelines for comparison).")
    args = p.parse_args()

    in_path = Path(args.in_path)
    if not in_path.exists():
        sys.exit(f"input not found: {in_path}")
    out_path = Path(args.out_path) if args.out_path else in_path.with_name(
        in_path.stem + "-judged" + in_path.suffix)
    summary_path = Path(args.summary) if args.summary else out_path.with_suffix(".md")

    rows = [json.loads(line) for line in in_path.read_text().splitlines() if line.strip()]
    print(f"loaded {len(rows)} rows from {in_path}")

    judged: list[dict] = []
    for i, row in enumerate(rows, start=1):
        if args.only_anchor and row.get("pipeline") != "anchor":
            judged.append(row)
            continue
        if not row.get("answer"):
            judged.append(row)
            continue
        try:
            verdict = judge_row(row)
            row.update(stance_match=verdict["stance_match"],
                       stance_judge_reason=verdict["reason"])
            print(f"  [{i:3d}/{len(rows)}] {row['pipeline']:7s} {row['kind']:7s} "
                  f"expected={row['expected_stance']:8s} → {verdict['stance_match']}")
        except Exception as e:  # noqa: BLE001
            row["stance_match"] = "no"
            row["stance_judge_reason"] = f"judge failed: {e}"
            print(f"  [{i:3d}/{len(rows)}] {row['pipeline']:7s} ERROR: {e}")
        judged.append(row)

    with out_path.open("w") as f:
        for row in judged:
            f.write(json.dumps(row) + "\n")
    summary_path.write_text(render_summary(judged))

    print(f"\nwrote {out_path}")
    print(f"      {summary_path}")


if __name__ == "__main__":
    main()
