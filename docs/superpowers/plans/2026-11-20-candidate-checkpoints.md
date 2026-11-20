# Candidate Checkpoints Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; the user already chose direct continuous implementation without repeated approval questions.

**Goal:** Allow one fresh checkpoint ladder per explicitly approved immutable candidate without reopening prior failed slots.

**Architecture:** Extend existing schema5 identity/path and approval preflight, not the question catalogue or scoring. Candidate key is JAR SHA, not process/receipt/runner hash.

**Tech Stack:** Existing Node standard library and official runtime scripts.

**Spec:** docs/superpowers/specs/2026-11-20-candidate-checkpoint-design.md

## Global Constraints

Preserve all old records,1003 questions/source/oracle/verifier, legacy behavior,
Atlas18080, sharedmain and paused automation. No old quality response replay.
Backend candidate31aac7fc/JAR881f6a11… is already verified; do not rebuild it.

## Review Focus

Same-JAR restart; altered receipt; cross-candidate predecessor; legacy consumed
candidate; ambiguous/missing legacy terminal identity must never free a slot.

### Task 1: Candidate identity, authorization and workflow

**Files:** scripts/lib/rag-eval-provenance.js; rag-versioned-artifacts.js;
rag-versioned-evaluation.js; rag-versioned-workflow.js; corresponding scripts tests.

**Interfaces:** Optional evaluationCandidateSha256 on schema5 identity; approval
checkpointPolicy=candidate-once-v1 and evaluationCandidateSha256=runtime JAR hash.
resolveReportPaths/reserveVersionedEvaluationSlot consume the new identity;
assertPriorVersionedCheckpoint inherits candidate through the same identity.

- [ ] Add synthetic tests proving distinct candidate paths, same-JAR repeat refusal, legacy-owner refusal/ambiguity, policy/JAR mismatch and cross-candidate incompatibility. Run focused tests and observe RED.
- [ ] Implement candidate paths and legacy-slot fencing; bind approved candidate at preflight and workflow identity. Keep legacy no-policy artifacts unchanged. Run focused tests; expect0fail.
- [ ] One fresh scoped review, one fix pass, stable full Node suite once. Reuse backend2269/f0/e0/skip85 for unchanged candidate. Commit/push owned changes only.
- [ ] Capture current source audit, generate candidate-bound request/receipt from actual user grant; official same-JAR registration and new instance manifest. Run candidate penalty then only eligible subsequent stages in unique slots. Never retry quality FAIL.
- [ ] Preserve exact evidence and deployment health; do not claim original Goal complete absent full1003/allblocking0 and all required work.
