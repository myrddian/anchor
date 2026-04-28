# Prompts

> Placeholder. The full prompts and tuning protocol are in
> [SPEC.md](../SPEC.md) §6 (and §6.7 for the protocol). The prompt files
> themselves live in
> [`anchor-server/src/main/resources/prompts/`](../anchor-server/src/main/resources/prompts/).

## Inventory

| File | Layer | Output shape | Temperature |
|---|---|---|---|
| `paragraph-summary.txt` | Ingest | One sentence | 0.0 |
| `section-summary.txt` | Ingest | 2–4 sentences | 0.0 |
| `chapter-summary.txt` | Ingest | 3–5 sentences | 0.0 |
| `doc-summary.txt` | Ingest | 3–6 sentences | 0.0 |
| `validation.txt` | `/validate` | Strict JSON (enum-driven) | 0.0 |
| `ask-proposer.txt` | `/ask` stage 1 | First-person prose | 0.3 |
| `ask-critic.txt` | `/ask` stage 2 | JSON challenge list | 0.0 |
| `ask-synthesiser.txt` | `/ask` stage 3 | Prose + JSON grounding | 0.2 |

Defaults above are starting points; see SPEC §7.5 for rationale.

## Tuning protocol (SPEC §6.7)

1. Run on 5 known cases from the chemistry corpus.
2. Chemist rates: correct / partially correct / wrong.
3. <80% correct → iterate the prompt and re-test.
4. Lock prompts under `resources/prompts/`; load via
   `@Value("classpath:prompts/...")`.

For deliberation prompts: tune proposer first (off-topic refusal), then critic
(against hand-crafted flawed proposer outputs), then synthesiser last with
full debate context.

## Sections to fill in (after Phase 0 spike)

- Per-prompt eval results and notes from Phase 0.
- Token-budget observations on Gemma 4 E4B.
- Failure modes observed during tuning and what they imply about the prompt.
