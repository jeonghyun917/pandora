# Pandora RAG quality final handoff — 2026-07-22 16:33 KST

## Completion state

- The atomic claim/evidence relation slice is complete on the latest app-dev artifact.
- No file was staged, committed, reset, reverted, or cleaned.
- All pre-existing tracked and untracked changes remain in the workspace.
- Only app-dev `8080` was redeployed. `18080` and its batch JAR were not changed.

## Root causes and generalized corrections

The original false contradiction cluster came from comparing polarity before the
claim and evidence were aligned to the same proposition. Long OCR fragments mixed
general rules, exceptions, conditions, forms, and unrelated opposite-polarity text.

The implementation now:

- atomizes structural, condition, exception, and compound-clause boundaries;
- aligns relation, condition, numeric, permission-action, subject, object, recipient,
  and target-scope anchors before polarity comparison;
- requires minimum coverage for contradiction candidates as well as support;
- treats form/template fragments as structural-only evidence;
- preserves a rule plus its attached exception as separate propositions;
- ignores leading summary discourse frames only for condition analysis;
- treats `~으로/에서/부터/에 따라 추진·수행·운영...하는` phrases as narrowing
  conditions, preventing a specific funding/source exception from contradicting a
  broader general rule;
- keeps the existing fail-closed `ClaimVerifier` policy unchanged.

The final live-discovered regression was:

```text
claim:    공공기관이 추진하는 정보화사업은 원칙적으로 사전협의 대상입니다.
evidence: 기관 자체 수입으로 추진하는 정보화사업은 사전협의 대상이 아닙니다.
```

It is now `INSUFFICIENT`, while the same `기관 자체 수입` proposition with opposite
polarity remains `CONTRADICTED`. Different funding/source conditions also remain
separate.

## TDD and verification evidence

- New resource-scope RED controls:
  - broad rule versus resource-limited exception: RED as `CONTRADICTED`;
  - different resource-limited propositions: RED as `CONTRADICTED`.
- Focused GREEN controls: `3/3`.
- Atomic matcher/verifier focused suite: `375/375`.
- Fresh full Maven suite: `729/729`, failures `0`, errors `0`.
- Node evaluator/provenance/retrieval suite: `42/42`.
- App-dev user-runtime PowerShell suite: `13/13` assertions.
- `git diff --check`: no whitespace errors; only the repository's existing
  LF-to-CRLF warnings.
- Deterministic artifact controls for the genuine direct contradiction and compound
  scope/source/procedure overreach remain fail-closed.

## Runtime identity

- `8080`: listening, PID `29236`, supervised user-runtime session `49957`.
- runtime instance: `9a6b2f7b-66b9-4e3d-9351-bee7539d4904`.
- app artifact size: `51,948,156` bytes.
- app artifact SHA-256:
  `8EC365C71ABBEBE6184B2CEADFB270F5DED3A8BB99DD8DAC2646450105A7FC3B`.
- staged and deployed app JAR hashes are identical.
- runtime config SHA-256:
  `78123730c2a8655665fcde0590e57ac52acfce2c5cc54641ca23548842c7bfdb`.
- index revision:
  `09623cdf28cdcd1e89baa7541cecb9ca12c0a9cf6f8393283dcb8a8c33c84fd2`.
- `6333` Qdrant: listening, PID `7172`; ready; search failures `0`.
- `18080`: not listening; stale PID file remains `7504`.
- batch JAR: `51,865,006` bytes, updated `2026-07-15 13:12:20`, SHA-256
  `AF28683C14234099F95A78BCC0E64333BC3196814225ADE3ECB4709A59291ED3`.

## Repeated targeted evaluations

Latest two newly discovered resource-scope cases:

- run 1: gate `0/2`, contradiction/conflict links `0`, both safely refused;
- run 2: gate `1/2`, `SUPPORTED` links `1`, contradiction/conflict links `0`;
- both runs used the exact same artifact/config/index/instance.

Latest exact 38-case safety set:

- run 1: gate `2/38`, answer verification `2/38`, `SUPPORTED` links `2`,
  contradiction/conflict links `0`;
- run 2: gate `7/38`, answer verification `7/38`, `SUPPORTED` links `12`,
  contradiction/conflict links `0`;
- the 33 historical false contradiction cases did not regain a false
  `CONTRADICTED` or `CONFLICTED` link;
- generated-answer usefulness remains unstable (`2/38` versus `7/38`).

Artifacts:

- `logs/rag-eval-gate-targeted-resource-scope-final-run1-20260722.json`
- `logs/rag-eval-gate-targeted-resource-scope-final-run2-20260722.json`
- `logs/rag-eval-gate-targeted-atomic-relations-final6-run1-20260722.json`
- `logs/rag-eval-gate-targeted-atomic-relations-final6-run2-20260722.json`

## Final full 1,004-case evaluation

Canonical final result:

- output: `logs/rag-eval-gate-full-atomic-relations-final2-20260722.json`;
- output SHA-256:
  `973D3F3CC936780D9693E6918A78009DACB725594E45B098CE0CAD431B5DE37D`;
- report: `logs/rag-eval-gate-full-atomic-relations-final2-20260722.md`;
- report SHA-256:
  `970685393D48AE508E2E45DD7BCC4034835DD181EABF83376B36B3A46573C8A4`;
- checkpoint: `logs/rag-eval-gate-full-atomic-relations-final2-20260722-checkpoint.json`;
- checkpoint SHA-256:
  `4EF13D368B6DFC87B5A394816C2906D1C632CDE66F9D417A5CA20405C97AC6C0`;
- gate: `925/1,004`;
- total: `1,004`; passed: `925`; failed: `79`; pass rate: `92.13%`;
- curated: `66/145` (`45.52%`);
- generated retrieval cases: `859/859` (`100%`);
- answer verification: `7/85`; failed: `78`; unsupported-claim cases: `84`;
- final safe refusals among answer-verification cases: `76`;
- relation links: `SUPPORTED 12`, `CONTRADICTED 0`, `CONFLICTED 0`;
- result integrity: expected/actual/unique IDs all `1,004`, duplicates `0`,
  evaluation errors `0`;
- one transient generated case was retried and then passed;
- Qdrant search failure count: `0`;
- full-run artifact/config/index/instance exactly match the targeted runs.

The earlier 2026-07-20 baseline was `952/1,004`, with answer verification
`34/85`. The lower aggregate now is intentional evidence of a stricter fail-closed
verifier, not weaker retrieval. It prevents unsupported answers but currently
over-refuses.

## Honest quality score

Current score: **7.4/10**.

- retrieval and source selection: `9.0/10`;
- unsupported/wrong-answer prevention: `8.8/10`;
- answer usefulness and coverage: `4.5/10`;
- run-to-run answer stability: `5.5/10`;
- provenance, evaluation integrity, and runtime operations: `9.0/10`.

The overall `92.13%` gate number must not be reported as a 9.2/10 product-quality
score because `859` generated cases do not exercise answer verification and only
`7/85` strict answer cases passed.

## Next bounded priority

The next task is generated-answer grounding and salvage, not broader retrieval.

1. Constrain generation to one atomic direct conclusion plus only directly cited
   details from selected ground numbers. Do not combine rule, exception, scope,
   source, and procedure into one sentence.
2. When verification finds unsupported expansion but at least one direct ground,
   perform at most one verifier-guided grounded rewrite using supported evidence
   atoms. If no supported atom remains, keep the current standard refusal.
3. Cache and stream only the verified final form; never cache the rejected draft.
4. TDD against the 85 answer-verification cases and the genuine fail-closed artifact
   controls, then run that set twice before another full 1,004-case run.

First milestone: improve stable answer verification materially above `7/85` with
zero `CONTRADICTED`/`CONFLICTED` safety regression. Do not relax the fail-closed
policy merely to raise the gate number.
