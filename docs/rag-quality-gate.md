# RAG Quality Gate

The primary goal is to prevent unsupported or wrong answers from reaching users.

## Pipeline Guardrails

1. Retrieve broadly.
   - Vector, keyword, title, section, parent context, and synonym-expanded queries can all contribute candidates.

2. Select narrowly.
   - Final grounds must be direct evidence for the user question.
   - Supporting evidence can be used only after at least one direct ground exists.

3. Verify answer claims.
   - Strong claims such as obligation, prohibition, exception, deadline, amount, sanction, or eligibility must be supported by selected grounds.
   - Numeric claims are removed when the selected grounds do not contain the same numeric value.

4. Fail closed.
   - If direct grounds are missing, return a no-grounds answer instead of guessing.
   - Store the failure with type/stage/retryability so it can be reviewed.

## Evaluation Case Fields

Each evaluation case should define:

- question
- expected target document type
- expected document title or aliases
- expected page or section when known
- expected parent/section terms
- required direct evidence terms
- forbidden terms
- expected no-grounds result when the system should refuse

## Required Gate

Every RAG logic change should run:

```powershell
.\mvnw.cmd test
node .\scripts\rag-eval-gate.js
```

The gate must pass with zero failures before promotion.

## Document-first Candidate Expansion Gate

Document-first candidate expansion is shadow-only until its separately
approved promotion ladder passes. The committed safety bounds are three
documents, eight chunks per document, and 24 unique chunks globally.

- Keep `law-ai.retrieval.document-expansion.authoritative=false` before
  promotion.
- Preserve the vector, lexical, pure-RRF, coverage-aware, and final answer
  control orders while authority is false.
- Treat invalid bounds, malformed document/chunk identity, ambiguity, database
  failure, timeout, and provenance drift as baseline fallbacks.
- Require an immutable manifest and exact approval before sending evaluation
  questions to an OpenAI API. Do not infer approval from a prior or broader
  evaluation.
- Do not consume difficult or holdout cases unless the frozen training gate
  passes twice without baseline regression.

The implementation verification record is
[`docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`](superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md).

For the full local gate, the script writes a checkpoint after every batch:

```text
logs/rag-eval-gate-checkpoint.json
```

If a long run is interrupted, resume it explicitly:

```powershell
$env:RAG_EVAL_RESUME='1'
node .\scripts\rag-eval-gate.js
```

## Failure Review Loop

1. User asks a question.
2. Search finds no direct evidence, Judge rejects all candidates, or Claim Verifier rejects the answer.
3. The system stores a failure record with failure type and stage.
4. Admin reviews the failure.
5. If the question is valid, promote it to the evaluation set.
6. Improve dictionary/query planner/chunk metadata/retrieval.
7. Re-run the evaluation gate.

## Quality Dashboard Signals

The admin dashboard should be treated as unhealthy when any of these are non-zero or failed:

- embedding backlog
- Qdrant exact delta
- failed eval gate
- open failure candidates
- residual tiny chunks requiring review
- RAG noisy short chunks

## Rollback Rule

Keep old chunks and index versions until the new version passes the evaluation gate. Disable old versions only after:

- eval gate passes
- representative manual tests pass
- Qdrant/DB backup exists
- rollback collection or chunk version is known
