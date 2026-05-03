# §6.7 prompt tuning protocol

The eight Anchor prompts (paragraph / section / chapter / doc summary;
validation; ask-proposer / -critic / -synthesiser) ship with
working-but-untuned defaults under `anchor-server/src/main/resources/prompts/`.
Phase-1 code uses them as-shipped; Phase-2/3 promotion to "locked" requires
the protocol below — adapted from SPEC §6.7.

## When to run this

- **Once per prompt.** After locking, only re-tune if the underlying chat
  model changes (e.g. swapping Gemma 4 E4B for a successor) or if a regression
  is observed in real use.
- **Always against the same chemistry corpus** so successive runs are
  comparable. Five papers minimum; ten preferred.

## The five-case rubric

For each prompt, hand-pick five cases from the corpus:

| Case | What it stresses |
|---|---|
| 1. **Steelman-then-refuted** | The chunk locally argues for X; the document later rejects X. The hardest argumentative-role call. |
| 2. **Author position with strong evidence** | The chunk is the author's central claim, well-grounded. Should be the easy case. |
| 3. **Cited external view** | The chunk paraphrases another paper's view that the author neither endorses nor rejects. |
| 4. **Background factual** | The chunk states a domain fact with no argumentative load. |
| 5. **Off-topic for the query** | The chunk is irrelevant to the query — tests refusal / `OFF_TOPIC` stance. |

The same five cases run against the same prompt at every iteration. **Don't
swap cases between iterations** — that destroys comparability.

## Scoring

For each case, you (the chemist eyeballing) rate the model's output as one of:

- **Correct** — judgment matches what you'd write yourself.
- **Partial** — gets the main call right but misses a qualification, or vice
  versa.
- **Wrong** — material misreading.

Threshold to lock: **≥ 80 % correct on argumentative role for steelman cases**
(per the SPEC). In a five-case run that's 4 / 5; partial counts as half.

## Iteration loop

```
1. Run the prompt across all 5 cases. Save the raw outputs.
2. Score each on the rubric (one row per case in the worksheet below).
3. If pass-threshold met → lock the prompt, commit it.
4. Otherwise: read the failures together. The fix is usually one of:
   - The prompt is asking for the wrong thing (e.g. "discusses" not "claims")
   - The prompt's enum vocabulary is missing a case
   - The model is parroting an instruction (drop it)
   - The temperature is too high for the task (lower it)
5. Edit the prompt minimally. One change per iteration. Re-run from step 1.
```

The "one change per iteration" rule matters: model behaviour shifts in
non-obvious ways and it's hard to attribute regressions when several things
moved at once.

## Worksheet (copy per prompt × per iteration)

Save under `docs/evaluation/runs/<YYYY-MM-DD>-<prompt>-<iteration>.md` so the
history is auditable.

```markdown
# <prompt>: iteration N — YYYY-MM-DD

Prompt SHA: <git sha of the prompt file>
Model:     <chat model + version>
Temperature: <temp>

| # | Case | Expected | Got | Score | Notes |
|---|---|---|---|---|---|
| 1 | Steelman-refuted: <chunk_id> | role=STEELMAN_REFUTED_LATER, stance=REJECTS | … | correct/partial/wrong | … |
| 2 | Author position: <chunk_id> | role=AUTHOR_POSITION, stance=SUPPORTS | … | … | … |
| 3 | Cited external view: <chunk_id> | role=CITED_EXTERNAL_VIEW, stance=NEUTRAL | … | … | … |
| 4 | Background fact: <chunk_id> | role=BACKGROUND_FACTUAL | … | … | … |
| 5 | Off-topic: <chunk_id> | stance=OFF_TOPIC | … | … | … |

**Score:** correct=N partial=N wrong=N → effective = N/5
**Decision:** lock / iterate (next change: …)
```

## Order of tuning for the deliberation prompts (SPEC §6.6 / ANC-12)

The three ask prompts must be tuned in this order — earlier ones become test
material for later ones:

1. **Proposer alone.** Five off-topic queries. Pass: clean refusal without
   fabrication. Hand-craft the queries; don't reuse the validation cases.
2. **Critic alone.** Five hand-crafted *deliberately-flawed* proposer outputs.
   Pass: catches the flaw. Track `challenges_incorporated / challenges_raised`
   over time as a sanity check on critic eagerness — too many false-positive
   challenges and the synthesiser learns to reject them all.
3. **Synthesiser last.** Five full proposer + critic transcripts (use the
   passing cases from steps 1 and 2). Pass: doesn't fabricate, correctly
   refuses off-topic, incorporates valid critic challenges, rejects spurious
   ones with a noted reason.

**Don't tune them concurrently.** Each one's inputs depend on the upstream
prompt's behaviour being stable.

## Files this protocol touches

- `anchor-server/src/main/resources/prompts/*.txt` — the prompt under tuning
- `docs/evaluation/runs/*.md` — per-iteration worksheets (gitignored or
  committed depending on whether the corpus is public)
- Linear ticket comments — paste the worksheet's score line so progress is
  visible without checking out the repo
