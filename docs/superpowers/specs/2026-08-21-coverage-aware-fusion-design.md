# Coverage-Aware Fusion Design

## 1. Goal

Improve direct-ground recall when pure chunk-level reciprocal rank fusion (RRF)
pushes a useful sibling chunk below the top-30 boundary even though another
chunk from the same document is already a strong cross-source result. The new
ranking remains shadow-only until it passes the existing Task 15 training,
difficult-12, holdout, safety, repeatability, and latency gates.

The acceptance target is at least `7/8` explicit-oracle all-required recall on
the difficult-12 set without a false-ground regression. The untouched holdout
is not consumed until one configuration is selected on the frozen independent
training split and passes the difficult-12 twice.

## 2. Decision

Add a bounded post-RRF sibling-rescue stage. It starts from the existing pure
RRF order and may replace at most two tail entries inside the top 30 with
already-retrieved candidates from documents that have a strong cross-source
anchor in that top 30.

This is preferred over further global weight tuning because the verified
`1.0/0.75` experiment did not improve the difficult-12 acceptance result and
regressed one previously passing case. It is preferred over document-level
reranking because whole-document boosts can promote unrelated sibling chunks
and weaken Pandora's narrow direct-ground selection policy.

## 3. Scope

This slice adds:

- a pure Java coverage-aware fusion component after existing RRF;
- document identity in bounded rank-capture artifacts;
- an offline selector that replays the same algorithm over the two frozen
  24-case training captures;
- shadow debug and candidate-loss trace fields for baseline and rescued ranks;
- guarded configuration for the selected rescue policy; and
- focused, full-suite, difficult-12, holdout, and release-gate verification.

It does not:

- change vector search, BM25 tokenization, BM25 scoring, or the RRF formula;
- fetch new sibling chunks from MariaDB or Qdrant;
- use question text, answer-oracle groups, audit aliases, or document-specific
  rules in production ranking;
- tune on difficult-12 or holdout outcomes;
- enable RRF or semantic authoritative mode before every existing gate passes;
- touch the `18080` batch runner or the untracked `output/` directory; or
- add an external API call to normal user-facing retrieval; bounded evaluation
  runs remain separately approval-gated.

## 4. Inputs and Identity

The stage consumes:

- the existing pure RRF list, bounded to the configured fused limit of 100;
- the existing hydrated `LawSemanticChunkRow` map keyed by `target:chunkId`;
- each RRF candidate's vector rank, BM25 rank, and fused rank; and
- each hydrated candidate's target, chunk ID, and document ID.

Document identity is `target:documentId`. A non-positive document ID, a missing
hydrated row, or a target mismatch makes that candidate ineligible for rescue.
The stage never guesses document identity and never performs another database
lookup.

Training rank captures add numeric `documentId` to every vector and BM25
candidate. They continue to exclude chunk text, snippets, and question text.
Conflicting document IDs for the same candidate invalidate the capture.

## 5. Deterministic Rescue Algorithm

The baseline is the current pure RRF order with weights `1.0/1.0`, `k=60`, and
top K `30`.

An anchor is eligible when:

1. it is inside the baseline top 30;
2. both its vector rank and BM25 rank are present; and
3. it has a valid document identity.

A sibling is eligible when:

1. it is outside the baseline top 30 but inside the captured fused union of at
   most 100 candidates;
2. it has the same target and document ID as an eligible anchor;
3. its best source rank is less than or equal to the configured maximum source
   rank; and
4. it is not itself a cross-source anchor.

The initial, predeclared selector grid is:

- baseline: no rescue;
- one rescue, one per document, source-rank limit 20;
- one rescue, one per document, source-rank limit 30;
- two rescues, one per document, source-rank limit 20; and
- two rescues, one per document, source-rank limit 30.

No additional grid point may be added after observing difficult-12 or holdout
results.

Eligible rescue proposals sort by:

1. ascending anchor fused rank;
2. ascending sibling best source rank;
3. ascending sibling baseline fused rank;
4. target;
5. document ID; and
6. chunk ID.

At most one sibling is selected per document. A selected sibling replaces the
lowest-ranked replaceable top-30 candidate. Cross-source anchors are never
replaceable. Replacement candidates are considered from rank 30 upward. After
the required number of tail candidates is removed, the survivors retain their
relative baseline order and selected siblings are appended in rescue-priority
order, preserving exactly 30 unique candidates.

The algorithm returns both the unchanged baseline order and the coverage-aware
order, plus immutable rescue records containing candidate key, document key,
anchor key, baseline rank, rescued rank, and reason
`DOCUMENT_SIBLING_RESCUE`. It does not inspect required audit groups.

## 6. Training Selection

The existing immutable 24-case training manifest remains the only optimizer
input. The two existing error-free rank captures are reusable only after a
new capture schema can supply document identity. If the old artifacts lack
that field, the exact 24 questions are captured twice again under explicit
external-execution approval and one stable runtime/JAR/config/index/lexical
fence.

Each capture is replayed independently. A non-baseline rescue configuration is
eligible in one run only when it:

1. improves all-required top-30 count over pure RRF;
2. preserves every case that passes all-required under pure RRF;
3. does not reduce any-required count;
4. does not reduce total matched required-group count; and
5. stays within the fixed maximum of two rescues.

Eligible configurations sort by:

1. highest all-required count;
2. highest any-required count;
3. highest total matched-group count;
4. fewer rescues;
5. lower source-rank limit; and
6. the declared grid order.

Both captures must select the same guarded winner. Otherwise the selector
returns baseline with `NO_STABLE_COVERAGE_IMPROVEMENT`. No eligible improvement
returns `NO_COVERAGE_IMPROVEMENT`. The selector writes a recommendation
artifact but never edits runtime configuration.

## 7. Runtime Integration

Add a focused configuration group under `law-ai.retrieval.coverage-aware`:

- `enabled=false` by default;
- `max-rescues=0` by default;
- `max-rescues-per-document=1`; and
- `source-rank-limit=30`.

Invalid negative values fail configuration validation. When `max-rescues` is
positive, a per-document limit greater than the global limit also fails
validation. Enabling the stage with zero rescues is outcome-identical to
baseline.

When enabled, `LawAiAnswerService` computes the coverage-aware shadow list
after candidate hydration and keeps the pure RRF list for comparison. The
control path remains unchanged while `rrf-authoritative=false`. If the full
promotion ladder later authorizes RRF, the selected coverage-aware list becomes
the RRF candidate order only when both `coverage-aware.enabled=true` and
`rrf-authoritative=true`.

Disabling either flag restores the verified pure-vector control path. Disabling
coverage-aware while retaining RRF authoritative restores pure RRF.

## 8. Diagnostics and Fail-Closed Behavior

Debug/evaluation output records:

- pure RRF rank;
- coverage-aware rank;
- anchor candidate key for a rescue;
- rescue reason; and
- counts of proposed, applied, and rejected rescues.

Candidate-loss traces add a `coverage-fused` stage so a required candidate can
be distinguished as absent from both sources, below the source-rank boundary,
ineligible due to document identity, or displaced at the final top-K boundary.

The stage returns the unchanged pure RRF order when:

- candidate hydration is incomplete or document identity conflicts;
- configuration is invalid at runtime;
- the result would contain duplicate candidate keys;
- deterministic replacement cannot preserve exactly K unique candidates; or
- any unexpected exception occurs.

The failure is logged and visible in shadow diagnostics. It never changes the
control path, fabricates a candidate, retries an external request, or bypasses
an existing evidence gate.

## 9. Verification and Promotion Ladder

Implementation follows red/green TDD:

1. Java unit tests prove anchor eligibility, document isolation, deterministic
   ordering, per-document/global budgets, tail replacement, duplicate safety,
   invalid identity fallback, and baseline outcome identity when disabled.
2. Node tests prove capture validation, Java-equivalent replay, independent-run
   selection, regression guards, deterministic tie-breaking, and fail-closed
   provenance behavior.
3. Focused `LawAiAnswerService` tests prove the control order is unchanged in
   shadow mode and that no additional database or external API request occurs.
4. Run the retrieval Node suite and full Maven backend suite.
5. Build and deploy one verified JAR only to app-dev `8080`; verify runtime,
   JAR, config, index, lexical, Qdrant, and listener fences.
6. If training selects a stable non-baseline configuration, run difficult-12
   twice with K 30 and concurrency 1. Require identical coverage-aware ranks,
   at least `7/8` all-required recall, no baseline-passing case regression,
   zero request errors, zero false-ground regression, and warm p95 at most
   500 ms.
7. Only after step 6 passes, run the untouched explicit-oracle holdout twice
   and require the existing recall, safety, provenance, and repeatability gates.
8. Continue through the existing 85-case and full release gates. Enable no
   authoritative flag unless all gates pass.

Any failed gate restores the verified baseline configuration and stops. The
failure result cannot authorize another grid change based on difficult or
holdout observations.

## 10. Operational Safety

- Use app-dev `8080`, Qdrant `6333`, and documented runtime scripts only.
- Never start, stop, restart, promote to, or modify `18080`.
- Never read, modify, move, or delete the untracked `output/` directory.
- Preserve runtime/JAR/config/index/lexical provenance in every external
  evaluation artifact.
- Require exact execution approval before any new OpenAI Embedding or Answer
  API payload is sent.
- Fail closed on API, Qdrant, database parity, rank stability, provenance,
  artifact, or listener ambiguity.
