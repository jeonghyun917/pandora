# Group-Balanced BM25 Retrieval Design

Date: 2026-09-02 (Asia/Seoul)

## Goal

Improve retrieval recall by allowing distinct question concepts, conditions, and exceptions to contribute lexical candidates independently. The candidate must discover at least one required evidence group that the control retrieval misses, without removing any required group already found by the control.

## Context

The current BM25 path flattens the original question and planned keywords into one term set. It then selects at most six posting terms under a shared posting-document budget. Rare or early terms can consume that budget, so a condition or exception term may never receive an independent retrieval opportunity.

The rejected title-plan expansion candidate selected more documents but added no required evidence group that was absent from the control. The next candidate therefore targets source discovery rather than title threshold relaxation.

## Selected Approach

Run bounded BM25 searches for deterministic lexical query variants already derived from `QuestionSearchPlan`, then combine those variant result lists with deterministic reciprocal-rank fusion.

The candidate uses only question-derived production data:

- the original normalized question;
- focused keywords;
- concept, intent, direct-evidence, and synonym groups already produced by the planner;
- existing target restrictions.

Evaluation oracle terms, case identifiers, expected document titles, and question-specific exceptions must never enter production query generation or ranking.

## Alternatives Rejected

### Increase the BM25 result or posting budget

This increases database work and noise without ensuring that a missing condition receives representation. It also makes latency and ranking changes harder to attribute.

### Expand dictionaries for the failed training cases

This risks case-specific tuning and does not address the general starvation mechanism. Dictionary changes remain appropriate only when an independently justified domain synonym is missing.

### Relax document-title thresholds again

The previous candidate already showed that broader title matching added duplicate evidence rather than novel required groups. Repeating that direction lacks supporting evidence.

## Components

### Lexical variant planner

`QuestionSearchPlan` exposes a bounded list of lexical variant queries. Variants are generated deterministically in this priority order:

1. original question plus focused keywords;
2. entity or primary concept plus the highest-priority intent terms;
3. direct-evidence or condition terms plus the primary entity or concept;
4. synonym-group representatives plus the highest-priority intent terms.

Empty variants and variants with identical normalized token sets are removed. The maximum is four variants, matching the existing expanded-query bound. Each variant must contain at least one substantive non-weak term.

### Independent BM25 searches

`KoreanBm25SearchService` executes each lexical variant as an independent bounded search. Each search keeps the existing term-count, posting-budget, target, score, rank, and result-limit safety checks. A variant cannot borrow posting budget from another variant.

The existing single-query BM25 result remains the control. Variant search failures, malformed results, unavailable index revisions, or invalid inputs produce an empty candidate and never alter the control result.

### Deterministic variant fusion

A focused `LexicalVariantFusion` component combines variant hit lists using reciprocal rank:

`score(candidate) = sum(1 / (k + rank_in_variant))`

The initial shadow constant is `k = 60`. Ties are ordered by fused score descending, best rank ascending, target ascending, chunk ID ascending, and document ID ascending. Candidate identity is `target:chunkId`. Matched terms are the sorted union from contributing variants.

The component enforces these bounds:

- at most four input variants;
- at most the caller's existing lexical result limit per variant;
- no duplicate candidate identity in the output;
- only positive finite source scores and fused scores;
- only positive ranks, chunk IDs, and document IDs.

Any invalid source list makes the variant candidate fail closed rather than partially fusing ambiguous data.

### Shadow integration and capture

The group-balanced result is shadow-only initially. It must not change vector results, current BM25 results, existing RRF results, judged grounds, selected grounds, answers, or failure logging authority.

Retrieval diagnostics capture:

- generated variant count and normalized variant hashes;
- per-variant hit count;
- each output candidate's contributing variant ranks;
- candidate source presence by required evaluation group;
- control and shadow-fused presence metrics;
- time spent in planning, variant searches, and fusion.

Oracle matching is applied only in evaluation reporting after retrieval and never feeds ranking.

## Configuration

Add lexical configuration with conservative defaults:

- `variantShadowEnabled=false` in the default configuration;
- `variantAuthoritative=false` in every committed configuration;
- `maxVariants=4`;
- `variantRrfK=60`.

Invalid bounds disable the candidate and preserve the baseline. An authoritative flag may be implemented for a later promotion commit, but it must remain false until every promotion gate passes.

## Data Flow

1. Build the existing `QuestionSearchPlan`.
2. Execute the unchanged vector and single-query BM25 control paths.
3. When variant shadow is enabled, build bounded lexical variants.
4. Search each distinct variant independently against the same ready lexical revision and target restrictions.
5. Fuse valid variant lists deterministically.
6. Capture the group-balanced ranks and evaluation-only oracle presence.
7. Return the unchanged control retrieval and answer while authority is false.

## Failure Handling

- Missing or changing lexical revision: skip the candidate.
- Invalid variant or duplicate normalized token set: discard that variant before execution.
- Mapper, timeout, or database failure: return an empty shadow candidate and record a bounded reason code.
- Malformed hit, non-finite score, invalid identity, or invalid rank: reject the entire fused candidate.
- Runtime, artifact, configuration, index, database, or Qdrant provenance drift during evaluation: invalidate the run.
- Any control regression or unexplained nondeterminism: reject the candidate and stop later gates.

No candidate failure may degrade the control answer path.

## Testing

Use TDD for every production behavior change.

Focused tests cover:

- deterministic variant order and token-set deduplication;
- maximum four variants and substantive-term requirement;
- independent posting-budget use;
- reciprocal-rank calculation and exact tie breaks;
- matched-term union and candidate deduplication;
- malformed hit and partial-failure fail-closed behavior;
- shadow mode preserving the exact control order and answer inputs;
- committed authority flags remaining false.

Run the complete Maven backend suite after focused tests pass.

## Promotion Ladder

### Training

Freeze the existing ordered 24-case training set and exact candidate artifact. Run two independent evaluations. A candidate advances only when both runs:

- have zero request and runtime errors;
- reproduce the same control metrics;
- lose no required group present in the control;
- exceed control all-required recall or add at least one previously absent required group;
- agree on the added groups and candidate ordering;
- keep Qdrant search failures at zero and all provenance fences stable.

If training does not improve, revert candidate behavior and retain only independently useful tests or diagnostics.

### Difficult-12, holdout, and answer evaluation

Only a training-selected candidate advances. Run Difficult-12 first, then the frozen holdout set, then Answer API evaluation. Each gate requires no retrieval regression, no unsafe answer regression, stable provenance, and the predeclared metric threshold for that gate.

### Authority and full release gate

Only after all earlier gates pass may a separate configuration commit enable group-balanced lexical authority and any dependent RRF or semantic-selection authority. Run the approximately 1,004-case release gate on that exact artifact. Activation is rejected on any failure, unsupported-answer increase, index mismatch, or provenance drift.

## Runtime and Safety Constraints

- Use app-dev `8080` for logic and evaluation verification.
- Do not stop, restart, or modify batch-runner `18080`.
- Do not write to `output/`.
- Treat MariaDB and Qdrant as read-only during retrieval evaluation.
- Require immutable manifests for external evaluation payloads.
- Preserve previous artifacts and control configuration for rollback.

## Completion Criteria

This slice is complete when the shadow candidate and focused tests are implemented, the full backend suite passes, and the two-run training selector produces a terminal `SELECTED` or `NO_IMPROVEMENT` decision with archived evidence. Later gates run only after `SELECTED`.
