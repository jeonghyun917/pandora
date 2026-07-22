# Pandora RAG Quality Handoff - 2026-07-20

## Exact completion point

The post-hardening full answer evaluation is complete on one verified runtime.

- expected cases: 1,004
- actual results: 1,004
- unique results: 1,004
- missing / duplicate / unexpected / order mismatch: 0 / 0 / 0 / 0
- passed: 952
- failed: 52
- pass rate: 94.82%
- curated: 93/145 (64.14%)
- generated: 859/859 (100%)
- answer verification: 34/85 (40.00%)
- hard 12: 9/12

The generated set is much easier than the curated and answer-verification sets.
Do not use the 94.82% aggregate alone as the product-quality score.

## Canonical artifacts

- final JSON:
  `logs/rag-eval-gate-full-post-hardening-post-retry-fix-20260720.json`
- final report:
  `logs/rag-eval-gate-full-post-hardening-post-retry-fix-20260720.md`
- corrected checkpoint:
  `logs/rag-eval-gate-full-post-hardening-rerun-20260720-checkpoint.json`
- checkpoint before the post-retry fix:
  `logs/rag-eval-gate-full-post-hardening-rerun-20260720-checkpoint-before-post-retry-fix.json`

SHA-256:

- final JSON:
  `88403502979EC3065DC4E61859E824703B289E7E47CB11A657BA3EE18E2A9E90`
- final report:
  `D26E015D191845A61E5D29149DAAF780D3A184EAD65BB518BD0BF9C148896EB6`
- corrected checkpoint:
  `1924F1BD0F8F352D5584A4B3F805708CEBD02E47994535251F1BD796EAEA52F0`
- checkpoint before the post-retry fix:
  `0B075F03869BC8E964D53B235F86C15C4551DF725DDF7AD1572D6F3F8F90AAF6`

The older interrupted 460-result checkpoint and shutdown backup remain
byte-identical with SHA-256:

`B2747E5814E37446F6EA7B6CDE0754BF6B377DA88D4A40B2190980E4EE92FA1C`

## Provenance

- Git commit: `e74ae7d22219aa62f730aaf81f626ec4072e4e6e`
- Git dirty: true
- dataset hash:
  `90ca2dffdfb8fbe65314bb00f43133197cd951f45a7c96fac0f1a441dd5fd81c`
- selection hash:
  `ebe27ec8ca9327d7b71af730955379b38f485d7a477cbfdda51b017906833fb6`
- runtime artifact:
  `b48da10c1c62c454c6e53e3604a4901aaea1d2f479107bd031c3cbc8b3223a33`
- runtime instance:
  `043d59b7-c4f7-45bc-ae9f-c76e112625aa`
- runtime config:
  `78123730c2a8655665fcde0590e57ac52acfce2c5cc54641ca23548842c7bfdb`
- index revision:
  `4e81877ec8c39b184418efd1af5708d5eb2e286857605669141c24a1500eb032`
- Qdrant ready: true
- Qdrant search failures: 0

The evaluator checked the same runtime identity again after all batches and
retries before writing the final result.

## Failure analysis

The 52 failures are mutually classified as:

1. contradiction/conflict claim-verifier path: 38 (73.1%)
2. unsupported-only answer expansion: 12 (23.1%)
3. judge/retrieval no-ground: 1 (1.9%)
   - `security-review-notice-result`
4. forbidden-evidence selection: 1 (1.9%)
   - `public-data-open-use`

Of the 50 answer-verification failures, 47 returned the standard insufficient
evidence refusal and 3 retained only sanitized partial answers. No contradicted
or unsupported original claim was found verbatim in a passed verified answer.
This is strong fail-closed behavior, but difficult-answer availability remains
low.

Hard-12 failures:

- `egov-preliminary-review-target`
- `whistleblower-protection-scope`
- `whistleblower-disadvantage`

Compared with the 2026-07-16 pre-hardening full run:

- overall: +5 passed
- curated: +5 passed
- answer verification: +5 passed
- 16 IDs improved and 11 regressed

The 27 status flips show material model/run variability. This is not a clean
controlled A/B because the runtime artifact and index revision also changed.

## Post-retry checkpoint bug and fix

The first completed run produced a final 952/1,004 output after retrying five
transient `EVALUATION_ERROR` results, but its checkpoint remained at 947/1,004.

Root cause:

- batch checkpoints were written before `retryEvaluationErrors`;
- successful retry replacements existed only in the in-memory final output.

Minimal generalized fix:

- for batched runs, write the post-retry checkpoint only after final runtime
  stability succeeds;
- preserve checkpoint identity and timestamp metadata;
- keep checkpoint-only fields out of the final output.

TDD evidence:

- RED: 25 passed, 1 failed; checkpoint retained `EVALUATION_ERROR`
- GREEN: 26 passed, 0 failed
- independent review: no Critical, Important, or Minor findings

Real-artifact integration verification:

- all 101 batches skipped from the compatible checkpoint;
- the five transient IDs were retried;
- corrected checkpoint and final output both report 952/1,004;
- their complete `results` arrays have the same SHA-256;
- checkpoint metadata does not leak into the final output.

## Verification

- one-case runtime smoke: 1/1 passed
- Node evaluator/retrieval tests: 42/42 passed
- Maven full suite: 427/427 passed
- full evaluation: 1,004/1,004 exact IDs processed
- runtime artifact/config/index stable through the canonical run

## Honest score

Current score: **7.2/10**

- fail-closed safety and faithfulness: 2.7/3.0
- retrieval and direct-ground quality: 2.0/2.5
- curated/hard answer availability: 1.9/3.5
- evaluation strength and stability: 0.6/1.0

The system is substantially better at refusing unsafe claims than at answering
difficult compound questions completely.

## Runtime and safety

At the final check:

- 8080 app-dev: running
- Qdrant 6333: running; both collections green
- 18080 batch-runner: no listener and not touched

The fallback start left an app-dev PID file pointing to a reused `git.exe` PID
instead of the active Java listener. It was removed after verifying the active
8080 listener was PID 10988; the Windows service and listener were not stopped.
The 18080 stale PID file was not altered.

## Next safe sequence

1. Read `AGENTS.md` and this handoff.
2. Run `git status --short --branch` and preserve every dirty/untracked change.
3. Run the official runtime status script; never touch 18080.
4. Start with the 38 contradiction/conflict cases:
   - create atomic claim/evidence fixtures;
   - separate true conflict from relation/negation/exception false positives;
   - use TDD and a small targeted case set before any full run.
5. Address the 12 unsupported-only expansions by preserving supported clauses
   and omitting extras instead of rejecting the whole answer.
6. Fix the two retrieval-policy defects:
   - `security-review-notice-result`
   - `public-data-open-use`
7. Repeat curated and hard-case gates at least twice before another expensive
   1,004-case full run to quantify stability.

Do not reset, revert, clean, stage, commit, or overwrite unrelated dirty work.
