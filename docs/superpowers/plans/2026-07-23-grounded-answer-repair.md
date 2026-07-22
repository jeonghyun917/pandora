# Grounded Answer Verification And One-Shot Repair Implementation Plan

> **For Codex:** Execute this plan with strict red-green-refactor checkpoints and
> use verification-before-completion before reporting success.

**Goal:** Close wording-based claim-verification bypasses, reject supported but
nonresponsive remnants, encode explicit answer truth for 85 cases, and allow one
safe evidence-only repair attempt.

**Architecture:** Keep atomic claim/evidence matching as the factual gate. Add a
narrow structural classifier, a separate post-sanitization question-alignment
gate, a sidecar answer oracle shared by Java and Node, and a one-shot repair
orchestrator that reuses the same gates.

**Tech Stack:** Java 21/Spring Boot/JUnit 5/AssertJ, Node.js test runner, TSV
evaluation data, PowerShell runtime scripts.

---

### Task 1: Close the wording-based fail-open path

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/ClaimVerifier.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/ClaimVerifierTests.java`

1. Add failing tests for noun-form conclusions, bullets, colon fragments, and
   caution text with a substantive proposition.
2. Confirm they pass unchanged before the fix.
3. Replace cue-based exemption with default substantive matching and a narrow
   structural-label exemption.
4. Run `ClaimVerifierTests` and contradiction/atomic matcher regressions.
5. Review the diff for accidental weakening of conflicted/contradicted behavior.

### Task 2: Add question-to-answer alignment after sanitization

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/AnswerQuestionAlignmentVerifier.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/AnswerQuestionAlignmentVerifierTests.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/AnswerVerificationService.java`
- Modify: answer service outcome tests as needed

1. Write failing cases where a supported side statement survives but misses the
   asked subject, relation, condition, or conclusion.
2. Add positive cases for direct supported answers and multi-condition wording.
3. Implement the smallest profile/group-based alignment result with reason codes.
4. Add a question-aware verification overload and switch production/eval callers.
5. Run focused service and alignment tests, then self-review boundary behavior.

### Task 3: Make 85 answer oracles explicit

**Files:**
- Create: `src/main/resources/rag-answer-evaluation-oracles.tsv`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiEvalRequest.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiEvaluationCaseCatalog.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- Modify: `scripts/lib/rag-eval-cases.js`
- Modify: Node/Java evaluation tests

1. Add RED tests requiring exactly 85 complete, non-orphaned oracles.
2. Define proposition groups, required conditions, and forbidden expressions for
   each explicit answer-verification case.
3. Merge the sidecar in both loaders and reject inconsistent data.
4. Replace the one-term answer fallback with AND-group oracle evaluation.
5. Run Java catalog/service tests and Node parser/eval tests; review all 85 rows.

### Task 4: Add one-shot evidence-only repair

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/GroundedAnswerRewriter.java`
- Create: `src/main/java/com/kaces/pandora/ai/answer/GroundedAnswerRepairService.java`
- Create corresponding unit tests
- Modify: `src/main/java/com/kaces/pandora/infra/openai/OpenAiAnswerClient.java`
- Modify: `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`

1. Write RED tests for one successful repair, no repair on contradiction/no
   supported evidence, reverify failure, exception, and exactly-one-call behavior.
2. Implement a fakeable rewriter boundary and supported-atom selection.
3. Add the dedicated OpenAI repair adapter/prompt.
4. Use the repair orchestrator for normal and streaming answers; expose only the
   final verified text and preserve OK-only caching.
5. Run focused repair/outcome/cache/stream tests and inspect the integration diff.

### Task 5: Complete verification and runtime evaluation

**Files:**
- Modify only defects found by review/tests
- Update handoff/evaluation artifacts if the live gate completes

1. Review all changes for fail-open paths, recursive repair, weak oracle matching,
   unrelated edits, and 18080 mutation risk.
2. Run focused Java tests, all Node tests, relevant PowerShell tests, then
   `.\mvnw.cmd test`.
3. Record `scripts/status-pandora.ps1`; start Qdrant and deploy/restart only 8080
   through official scripts, verifying that 18080 PID/hash remains unchanged.
4. Run the 85 answer cases twice and compare stability and failure classes.
5. If stable, run the full 1,004-case gate and report measured scores, remaining
   risks, and the next highest-value slice.
