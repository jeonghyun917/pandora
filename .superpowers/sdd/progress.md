# Pandora RAG Quality SDD Progress

- [x] Preserve repository instructions, dirty worktree, and all prior handoffs/checkpoints.
- [x] Complete generalized claim/evidence hardening with focused tests and independent reviews.
- [x] Preserve the interrupted 2026-07-16 checkpoint at 460/1,004 and its byte-identical shutdown backup.
- [x] 2026-07-20: restore Qdrant after reboot; verify `law_chunks` and `rag_chunks_v4` green.
- [x] 2026-07-20: start only 8080 and verify JAR/config/Qdrant provenance.
- [x] Run a one-case answer-eval smoke: 1/1 passed.
- [x] Run the authoritative 1,004-case answer evaluation from zero on one runtime.
- [x] Validate 1,004 expected/actual/unique IDs with zero missing, duplicate, unexpected, or order-mismatched IDs.
- [x] TDD-fix post-retry checkpoint persistence: RED 25/26, GREEN 26/26, independent review clean.
- [x] Correct the real checkpoint by same-instance resume; checkpoint/output results now match.
- [x] Run all Node evaluator tests (42/42) and the full Maven suite (427/427).
- [x] Re-score quality with a safety/availability/stability rubric: 7.2/10.

Canonical full result (2026-07-20):

- output: `logs/rag-eval-gate-full-post-hardening-post-retry-fix-20260720.json`
- report: `logs/rag-eval-gate-full-post-hardening-post-retry-fix-20260720.md`
- checkpoint: `logs/rag-eval-gate-full-post-hardening-rerun-20260720-checkpoint.json`
- total: 1,004; passed: 952; failed: 52
- curated: 93/145
- generated: 859/859
- answer verification: 34/85
- hard 12: 9/12

Current runtime identity (2026-07-20):

- runtime instance: `043d59b7-c4f7-45bc-ae9f-c76e112625aa`
- artifact SHA-256: `b48da10c1c62c454c6e53e3604a4901aaea1d2f479107bd031c3cbc8b3223a33`
- config SHA-256: `78123730c2a8655665fcde0590e57ac52acfce2c5cc54641ca23548842c7bfdb`
- index revision: `4e81877ec8c39b184418efd1af5708d5eb2e286857605669141c24a1500eb032`

Next bounded quality task:

1. Adjudicate the 38 contradiction/conflict cases with atomic claim/evidence fixtures.
2. Salvage supported clauses in the 12 unsupported-only expansion cases.
3. Fix `security-review-notice-result` no-ground and trace the forbidden evidence in `public-data-open-use`.
4. Repeat curated/hard gates to measure run-to-run stability.

Constraints: preserve all dirty changes; never restart, stop, or promote 18080.

## 2026-07-22 atomic relation completion

- [x] Correct the 33 historical false contradiction/conflict outcomes with
  proposition-sized evidence and conservative alignment gates.
- [x] Preserve deterministic fail-closed controls for genuine contradiction and
  compound scope/source/procedure overreach.
- [x] TDD-fix the full-run-discovered general-rule versus resource-limited-exception
  regression while preserving the same-scope true contradiction.
- [x] Run focused matcher/verifier tests: 375/375.
- [x] Run fresh full Maven tests: 729/729.
- [x] Run evaluator/retrieval Node tests: 42/42.
- [x] Run app-dev user-runtime PowerShell tests: 13/13.
- [x] Deploy only 8080 with verified artifact SHA-256
  `8EC365C71ABBEBE6184B2CEADFB270F5DED3A8BB99DD8DAC2646450105A7FC3B`.
- [x] Repeat the exact 38-case safety evaluation twice with zero
  `CONTRADICTED`/`CONFLICTED` links.
- [x] Complete the final 1,004-case run: 925 passed, 79 failed, 0 evaluation
  errors, 1,004 unique IDs.
- [x] Re-score quality honestly: 7.4/10.

Canonical final artifacts:

- `logs/rag-eval-gate-full-atomic-relations-final2-20260722.json`
- `logs/rag-eval-gate-full-atomic-relations-final2-20260722.md`
- `logs/rag-eval-gate-full-atomic-relations-final2-20260722-checkpoint.json`
- `docs/rag-quality-handoff-20260722-final.md`

Next bounded quality task: ground-constrained atomic answer generation plus one
verifier-guided supported-claim rewrite, retaining the current fail-closed policy.
