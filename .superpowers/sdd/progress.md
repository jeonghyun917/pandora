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

## 2026-07-23 grounded answer verification and repair

- [x] Task 1: complete (`d43df5a1..a0e01201`); default substantive-atom
  verification implemented with RED/GREEN evidence, numeric/heading review fixes,
  focused regressions 51 + 255 + 17 passing, independent task review approved.
- [x] Task 2: complete (`a0e01201..b25ea16c`); question-aware alignment now
  requires proposition-local subject, relation, conditions, and conclusion;
  focused 434/434 and full Maven 766/766 passed; independent review approved.
- [x] Task 3: complete (`b25ea16c..6f7cdc52`); exact 85-case grouped
  proposition/condition/forbidden oracle, strict fail-closed Java/Node loading,
  discourse-aware polarity matching, 85/85 semantic audit, Node 48/48 and full
  Maven 794/794 passed; independent review approved after three bounded fixes.
- [x] Task 4: complete (`6f7cdc52..d57b170e`); matched-child-only,
  question-aligned supported atoms feed at most one rewrite and full reverify;
  normal/stream/eval share the fail-closed orchestrator, focused 171/171 and
  full Maven 821/821 passed; independent review approved after selection and
  prompt-injection hardening.
- [x] Task 5: broad review approved after two TDD fixes (`7e4679c3`,
  `076c49b0`); the runtime-discovered rewrite-drift regression was fixed by
  preserving preverified atoms verbatim (`f84e7e68`). Focused repair tests
  25/25, full Maven 828/828, and Node evaluator tests 48/48 passed.
- [x] Deploy only 8080 with artifact SHA-256
  `067C63A71AF398FD1CC83400AD9D5571142EC6902376E3072C046D1781E740B9`;
  keep 18080 at PID 15600 and batch JAR SHA-256
  `8EC365C71ABBEBE6184B2CEADFB270F5DED3A8BB99DD8DAC2646450105A7FC3B`.
- [x] Run the exact 85-case answer gate on the final runtime: 1/85 passed,
  0 evaluation errors, 70 safe refusals, 15 non-refusal answers, and zero
  unsupported, contradicted, or forbidden claims among those 15 answers.

Next bounded quality task: improve direct proposition and required-condition
coverage without weakening the verifier. The 85-case gate has 80 missing-
proposition cases and 74 missing-condition cases, so rank and retain question-
aligned evidence atoms before attempting broader generation changes.

## 2026-07-23 evidence coverage measurement

- [x] Shared workspace verified clean on `main`; isolated worktree created at
  `.worktrees/rag-evidence-coverage` on `codex/rag-evidence-coverage`.
- [x] Baseline full Maven suite passed: 828/828.
- [x] Design committed: `5f1c4aba`.
- [x] TDD execution plan committed: `4052f597`.
- [x] Task 1: complete (`4052f597..ab5b3f89`, review approved);
  focused 56/56 and full Maven 830/830 passed. Controller confirmed the
  unchanged `/api/law-data/ai/debug/**` admin protection and that the normal UI
  request omits the measurement flag. Minor final-review follow-ups: consider a
  serialization-level null-omission test and replace reflective service testing
  with a public debug/MVC path test if that boundary changes later.
- [x] Task 2: complete (`ab5b3f89..5be26ce4`, review approved after polarity
  hardening); Node 19/19 and representative Java matcher 20/20 passed.
  Canonical UTF-8 Java source is parsed in tests and matches Node marker tables
  with negations 8/8, bridges 33/33, and zero missing/extra entries.
- [x] Task 3: complete (`5be26ce4..ce3490b2`, review approved); new and
  legacy Node evaluation tests 42/42 passed, with all seven stages, per-item
  matching, candidate-source de-duplication, stable IDs, and first-loss
  summaries verified.
- [x] Task 4: complete (`ce3490b2..ef779cd3`, review approved); original RED
  was legacy 42 passing plus 6 intended failures. Independent review found two
  fail-closed gaps, fixed with RED 47/49 then GREEN 49/49. Final task review
  reported zero Critical, Important, or Minor findings.
- [x] Task 5: collect the 85-case diagnosis. The authoritative 85-case run on
  runtime `257e4771-82ee-4def-ad98-50e76e36e00a` had 1/85 verified answers,
  zero evaluation errors, 82 missing-proposition cases, and 74
  missing-condition cases. Proposition coverage fell from 73/111 at selected
  grounds to 7/111 supported-evidence groups; condition coverage fell from
  31/77 to 3/77. Repair diagnostics were 13 `REWRITE_ACCEPTED` and 72
  `NO_ALIGNED_SUPPORTED_ATOM`. Twenty-five cases had all required groups in
  selected grounds but still missed them in the answer; 23 of those had no
  supported-evidence group. Canonical artifacts:
  `logs/rag-eval-gate-evidence-coverage-85-20260724.json` and
  `logs/rag-evidence-coverage-85-20260724.json`.
- [x] Task 6: apply only the measurement-justified generalized fix. The TDD
  slice preserves raw evidence provenance, lets an otherwise claim-supported
  matched-child atom borrow only missing subject/direct-conclusion alignment
  from an unambiguous structural title, and projects only explicit
  matched-child target-project label/value forms under one named procedure
  scope. Missing conditions, unlabeled phrases, dense structural sources,
  compound scopes, snippets, parent context, and category-only procedure names
  remain ineligible. Focused tests pass 324/324. Independent review approved the
  final slice with zero Critical, Important, or Minor findings after broad
  anchor-context and raw-label bypasses were closed.
- [ ] Task 7: self-review and complete verification. Runtime smoke exposed that
  structural atoms were selected but the verbatim rewriter could not restore
  the missing subject. A bounded deterministic projection now uses only an
  explicit matched-child target label/value plus one procedure scope, then
  fully re-verifies the projected atom. Three review cycles closed discarded
  condition tails, overlapping exclusions, and narrowing definitions without
  relaxing final verification. Focused tests pass 353/353, the fresh full Maven
  suite passes 852/852, `git diff --check` passes, and the final independent
  review reports zero Critical, Important, or Minor findings. Exact 85-case
  runtime re-evaluation remains. The final JAR was deployed only to 8080 with
  SHA-256 `084E5968EA9B3FBC9348D231F7EBD37EF5C0AC8F6BBC3642A851DEDCEC17ED2D`;
  the first one-case live evaluation is paused because sending the question and
  retrieved internal-document content to the configured OpenAI answer model
  requires explicit payload/destination authorization.
- [ ] Task 8: merge verified work without moving the shared workspace.
