# Pandora grounded answer repair handoff (2026-07-23)

## Completed scope

The requested four-part hardening is implemented on branch
`codex/rag-grounded-answer-repair` through commit `f84e7e68`:

1. Substantive noun phrases, bullets, cautions, and numeric fragments are claim
   verified by default; only format-only labels are exempt.
2. Surviving answers are rechecked against the question's subject, relation,
   condition, and direct conclusion.
3. The 85 answer-verification cases have explicit grouped propositions,
   required conditions, and forbidden expressions in
   `src/main/resources/rag-answer-evaluation-oracles.tsv`.
4. A failed answer may be rewritten at most once from question-aligned,
   supported matched-child atoms and is then fully reverified. Runtime testing
   showed that model paraphrasing could reintroduce unsupported claims, so the
   final rewriter preserves the preverified atoms verbatim.

The existing whole-answer fail-closed policy remains unchanged.

## Verification

- Focused repair and client tests: 25/25 passed.
- Full Maven suite: 828/828 passed.
- Node evaluator and retrieval tests: 48/48 passed.
- Final 85-case answer gate: 1/85 passed, 84 failed, 0 evaluation errors.
- Final non-refusal answers: 15; unsupported 0, contradicted 0, forbidden 0.
- Safe refusals: 70.
- Evidence relations: 52 `SUPPORTED`, 0 `CONTRADICTED`, 0 `CONFLICTED`.
- Missing proposition cases: 80; missing required-condition cases: 74.

Final targeted artifacts:

- `logs/rag-eval-gate-targeted-latest.json`
  - SHA-256 `D07CBA9F90E191851BEA0DE7BA7ADDDFE9651D0E2DA91765C22C4C361CBB4B9F`
- `logs/rag-eval-gate-targeted-latest.md`
  - SHA-256 `7318970781A7D5D1731CA0C81EAE6F43ADFA775A74A268EBA70061E4179662E0`
- `logs/rag-eval-gate-targeted-checkpoint.json`
  - SHA-256 `7853770597E08E63F88C6D2A34776F13B8C48DFB117DC160F9C5E3FA6EB23A4F`

## Runtime identity and protection

- 8080 PID: 3388.
- 8080 runtime instance: `3f8d264c-b8f5-4618-9b88-5cb7d5f30fb0`.
- 8080 JAR SHA-256:
  `067C63A71AF398FD1CC83400AD9D5571142EC6902376E3072C046D1781E740B9`.
- Qdrant PID: 17744; ready; search failure count 0.
- 18080 PID remained 15600.
- Batch JAR SHA-256 remained
  `8EC365C71ABBEBE6184B2CEADFB270F5DED3A8BB99DD8DAC2646450105A7FC3B`.

The concurrent user/batch-owner change in
`scripts/official-doc-batch-guard.js` was not modified, staged, or reverted.

## Honest current assessment and next step

The safety path is materially stronger, but answer usefulness is still low:
only one of the 85 strict proposition oracles passed. The next bounded task is
not to relax verification. Measure which required proposition and condition
terms are present in selected grounds, then rank and retain question-aligned
atomic evidence for direct conclusions and complete procedures. Re-run the 85
cases before spending time on another full 1,004-case gate.

The full 1,004-case gate was not rerun after the final atom-preserving change;
the exact 85-case answer gate is the authoritative final runtime evidence for
this task.
