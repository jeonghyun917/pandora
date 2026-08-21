# RAG Retrieval and Evidence Shadow Migration Design

**Date:** 2026-07-30
**Status:** Approved in chat
**Branch:** `codex/rag-direct-evidence-recovery`

## 1. Purpose

Pandora must improve recall and answer usefulness without weakening its
fail-closed safety policy. This design addresses six connected quality gaps:

1. regenerate a trustworthy baseline from one commit, JAR, configuration, and
   index revision;
2. clear the measured law embedding backlog and correct short, duplicate, and
   incorrectly parented chunks;
3. introduce one Korean BM25 lexical contract and rank-fuse it with vector
   retrieval through reciprocal rank fusion (RRF);
4. stop losing direct evidence after it has already entered the candidate set;
5. move claim/evidence verification to an explicit `EvidenceAtom` semantic
   model; and
6. make curated, answer-oracle, and `NO_GROUNDS` results independently blocking.

The migration is measured and reversible. Existing production decisions remain
the control path until the corresponding shadow implementation has passed its
acceptance gates.

## 2. Current-State Findings

### 2.1 Provenance mismatch

The evaluation runner already records runtime identity, but the latest available
full evaluation and retrieval diagnostics were produced from different commits,
JAR hashes, and index revisions. Their combined conclusions are directional,
not a valid baseline.

### 2.2 Corpus and index integrity

The latest audit measured:

- 4,272 active law chunks without a matching current embedding/index record;
- 10,428 active RAG chunks shorter than 120 characters;
- at least 1,026 non-keep short chunks in the audited short-chunk sample;
- 11,939 duplicate law content-hash groups covering 25,096 chunks;
- 388 duplicate RAG content-hash groups covering 1,984 chunks; and
- parent-context source groups capable of exceeding 832,000 characters before
  final answer-context truncation.

Law parent identity is currently reconstructed from title and source-path
heuristics. RAG chunks store a parent title but not a stable parent key. This
makes parent expansion and duplicate classification ambiguous.

### 2.3 Inconsistent lexical retrieval

Law/admin-rule lexical retrieval uses `LIKE` queries. RAG document retrieval
uses `rag_chunk_search_terms`, which stores one weighted term per chunk but does
not store term frequency, document frequency, or BM25 length statistics.

Vector and lexical scores are merged with `max(vectorScore, keywordScore)`.
Those scores are not calibrated to the same scale. A heuristic reranker then
contains many domain-specific branches that compensate for retrieval errors.

### 2.4 Post-retrieval evidence loss

The latest diagnostic showed direct-evidence hit rates falling through the
pipeline and only 32.6% of cases retaining direct evidence at final selection.
The pipeline records stage counts, but it does not maintain a candidate-level,
machine-readable reason for every transition and loss.

### 2.5 Monolithic semantic verification

`ClaimEvidenceMatcher` already extracts many semantic features, but they are
private nested structures inside a large matcher. Atomization, semantic parsing,
alignment, contradiction, and final status are coupled. There is no stable
`EvidenceAtom` contract and no control-versus-shadow comparison interface.

### 2.6 Monolithic evaluation gate

The full gate reports curated, generated, and answer-verification breakdowns,
but the breakdowns are informational. Curated, answer-oracle, and
`NO_GROUNDS` safety cases cannot be run and blocked as independent named gates.

## 3. Considered Approaches

### 3.1 Big-bang replacement

Replace chunking, lexical retrieval, rank fusion, Judge behavior, matcher, and
evaluation policy in one release.

This has the shortest nominal implementation path but makes quality changes
impossible to attribute. A regression could be hidden by an unrelated recall
gain. Rollback would also require restoring code, chunks, embeddings, and
selection behavior together.

### 3.2 Metrics-only tuning

Clear the embedding backlog, retune existing keyword weights, add more intent
rules, and keep the current matcher.

This is low effort but preserves incompatible lexical scoring, inferred parent
identity, candidate loss without structured reasons, and question-specific
matcher growth. It does not address the structural causes.

### 3.3 Measured shadow migration

Introduce versioned data contracts and shadow implementations one boundary at a
time. Keep the existing path authoritative until the new path passes focused
and full gates.

This is the selected approach because it separates corpus, retrieval, selection,
and answer-verification effects while preserving fail-closed behavior.

## 4. Invariants

The following invariants apply to every phase:

- The shared workspace remains on `main`; feature work stays in the existing
  worktree.
- Port 18080 and its batch runner are not stopped, restarted, promoted, or
  reconfigured.
- Runtime inspection uses `scripts/status-pandora.ps1`.
- Only port 8080 may be restarted for development verification, using the
  official start/stop scripts.
- Existing answer verification is never relaxed to make an evaluation pass.
- An uncertain semantic decision returns `INSUFFICIENT`, not `SUPPORTED`.
- Supporting evidence cannot replace missing direct evidence.
- Old chunks and enough metadata to re-index them remain available until the
  replacement passes all gates.
- No evaluation result is accepted if runtime provenance changes during the
  run.

## 5. Target Architecture

```text
source documents
  -> versioned parent/child chunk projection
  -> corpus integrity audit
  -> vector index + common Korean lexical index
  -> vector rank / BM25 rank
  -> RRF shadow fusion
  -> intent/rerank/Judge/final-selection trace
  -> grounds
  -> EvidenceAtom control + semantic shadow matcher
  -> answer/alignment verification
  -> independent blocking gates
```

The control and shadow paths consume the same query plan and the same stable
corpus snapshot. A shadow result must not modify the user-visible answer until
its phase-specific acceptance criteria are satisfied.

## 6. Phase 0: Exact Baseline

### 6.1 Run manifest

Add a baseline manifest containing:

- Git commit SHA and dirty state;
- JAR absolute path, size, timestamp, and SHA-256;
- runtime configuration SHA-256;
- runtime instance ID;
- embedding and answer model IDs;
- Qdrant collection names and exact point counts;
- database indexed counts and content fingerprints;
- dynamic index revision;
- evaluation dataset and selection hashes; and
- lexical index version and corpus revision.

The runner reads the manifest before and after evaluation. A changed or missing
required field invalidates the run. Full and resumed runs must use the same
manifest identity.

### 6.2 Baseline sequence

1. verify a clean tracked worktree;
2. build one JAR from the selected commit;
3. start or restart only 8080 with that JAR;
4. verify Qdrant and database/index parity;
5. freeze the manifest;
6. run focused, difficult, 85-case, and full evaluations without changing the
   runtime or index; and
7. archive the results by manifest ID.

The baseline is diagnostic and may fail quality gates. It is valid only when its
provenance gate passes.

## 7. Phase 1: Chunk and Index Integrity

### 7.1 Backlog classification

Classify each missing law embedding into exactly one cause:

- missing embedding row;
- retryable failed embedding;
- content-hash mismatch;
- embedding row present but Qdrant point missing;
- Qdrant point present but database state stale; or
- non-searchable/obsolete chunk incorrectly counted as active.

The repair action is selected from the cause. The system must not blindly
re-embed all 4,272 rows.

### 7.2 Versioned chunk identity

Introduce side-by-side, nullable-first metadata for law and RAG chunks:

- `chunk_schema_version`;
- stable `parent_key`;
- `parent_title`;
- `parent_source_path`;
- `child_order`;
- `embedding_text`;
- `quality_status`; and
- `quality_reason`.

`parent_key` is derived from document identity and the canonical source unit,
not from display text alone. Existing rows are backfilled in preview mode before
the new fields become required for newly generated chunks.

Law replacement no longer hard-deletes the previous chunk set immediately.
The old set is disabled only after the candidate set is text-audited, embedded,
found in Qdrant, and activated. Disabled rows remain available for rollback and
re-indexing until the final gate passes.

### 7.3 Quality classification

Every candidate chunk is assigned one of:

- `PASS`: independently searchable evidence;
- `CONTEXT_ONLY`: useful only through an explicit parent;
- `REVIEW`: ambiguous boundary or content loss risk; or
- `REJECT`: empty, decorative, contained duplicate, or classified noise.

Exact duplicates are assessed within current document/version and parent scope.
Cross-document duplicates are not removed merely because their hashes match;
they can be legitimate versions or officially repeated provisions.

### 7.4 Safe document replacement

For each document:

1. produce a preview with source length, projected child count, short chunks,
   duplicate groups, parent coverage, and normalized text-loss spans;
2. reject the replacement if unexplained text loss or boundary errors exist;
3. insert a candidate chunk version without disabling the active version;
4. embed and upsert the candidate points;
5. verify point count, vector size, payload identity, and content hash;
6. activate the candidate version;
7. remove obsolete active-collection points only after activation; and
8. retain the old database rows and a re-index recipe through final promotion.

### 7.5 Integrity acceptance

- active searchable law and RAG embedding backlog is zero;
- database and Qdrant exact counts and fingerprints agree;
- no active `REJECT` chunk is searchable;
- every searchable provision child has a stable parent key;
- every removed span has an explicit quality reason;
- normalized retained source coverage is at least 99.9%, excluding classified
  noise;
- no unexplained exact duplicate remains within one active document/version and
  parent; and
- parent expansion is bounded by explicit parent membership, seven chunks, and
  the existing 2,800-character answer-ground limit.

## 8. Phase 2: Common Korean BM25 and RRF Shadow

### 8.1 Common lexical contract

Replace source-specific lexical behavior with a common projection:

`semantic_lexical_chunks`

- target and chunk ID;
- document and parent identity;
- content hash;
- weighted token length;
- index version; and
- build status.

`semantic_lexical_terms`

- target and chunk ID;
- normalized term;
- field kind;
- term frequency; and
- field weight.

`semantic_lexical_term_stats`

- lexical revision;
- normalized term;
- document frequency; and
- corpus statistics needed for BM25.

The primary key includes target because law and RAG chunk IDs can overlap.

### 8.2 Korean tokenization

The initial implementation stays deterministic and local:

- Unicode normalization;
- Korean query normalization already used by Pandora;
- punctuation and structural-boundary tokenization;
- canonical legal article and numeric tokens;
- configured synonyms as query expansions, not duplicated corpus tokens;
- weak-question-term removal; and
- separate title, parent-title, chunk-title, and body field weights.

Tokenizer version is part of the lexical revision. A tokenizer change requires
a new shadow index build.

### 8.3 BM25 scoring

Use one documented BM25 formula and stable default parameters. Weighted field
term frequency may be used, but corpus length and document frequency are
calculated from the same common projection. Parameters are configuration, not
question-specific branches.

### 8.4 Reciprocal rank fusion

Vector and BM25 results remain separate ranked lists. Fuse ranks as:

`RRF(d) = sum(weight_i / (k + rank_i(d)))`

Initial values are configuration and are selected using a training subset.
The difficult and holdout evaluation sets are not used to hand-tune individual
questions.

### 8.5 Shadow behavior

The existing merged/reranked list remains authoritative. The shadow pipeline
records:

- vector rank;
- BM25 rank and matched terms;
- RRF score and fused rank;
- control rank;
- first stage containing each oracle proposition; and
- control-versus-shadow direct-evidence differences.

No user-visible ordering changes during this phase.

### 8.6 Retrieval acceptance

- common lexical index coverage is 100% for active searchable chunks;
- the shadow query has a bounded warm p95 latency target of 500 ms locally;
- explicit-oracle proposition presence at top 30 reaches at least 80%;
- difficult and negative-control cases have no direct-evidence regression;
- every rank difference is reproducible from stored ranks and configuration;
  and
- the full retrieval evaluation is run twice with the same manifest and yields
  identical deterministic rankings.

## 9. Phase 3: Judge and Final-Selection Loss

### 9.1 Candidate trace

Add a per-candidate trace record with:

- stable candidate key;
- source retrievers and ranks;
- rerank score components;
- intent-filter decision and reason;
- Judge input status;
- Judge topic, relevance, directness, and score decision;
- preservation/fallback decision;
- noise-filter decision;
- diversifier decision;
- final-ground status; and
- first loss stage and reason code.

The trace is available through protected debug evaluation and bounded log
artifacts. User questions and full private source text are not written into
unprotected logs.

### 9.2 Correction rule

Only cases where a required proposition is already present in candidates are
used to modify Judge or final selection. Candidate-absent cases return to
retrieval or chunking. A Judge change must explain a reusable failure class and
must preserve known true rejections.

### 9.3 Selection acceptance

- every oracle candidate loss has a non-empty stage and reason code;
- direct evidence already present in the Judge input is selected in at least 80%
  of answer-oracle cases;
- known true contradiction and unrelated-evidence controls remain rejected;
- no selection change depends on a single evaluation case ID or literal
  question; and
- fallback still runs at most once.

## 10. Phase 4: EvidenceAtom Semantic Matcher

### 10.1 Stable atom model

Introduce an immutable `EvidenceAtom` with:

- source text and source span;
- subjects;
- objects and recipients;
- predicate/action;
- relation anchors;
- target/scope anchors;
- conditions;
- exceptions;
- numeric anchors;
- modality;
- polarity; and
- parse confidence/reason codes.

Claims and evidence use the same model. Parser uncertainty is explicit.

### 10.2 Matcher responsibilities

Split the current matcher into:

1. structural atomizer;
2. deterministic Korean semantic parser;
3. proposition aligner;
4. condition, scope, and role coverage checker;
5. polarity/contradiction classifier; and
6. final status policy.

Contradiction is possible only after proposition alignment. `CONFLICTED`
requires aligned positive and negative evidence for the same proposition.
Missing roles, conditions, or scope fail closed as `INSUFFICIENT`.

### 10.3 Semantic templates

Replace growing answer-required string policies with reusable proposition
templates containing required and optional semantic slots. Dictionaries supply
canonical aliases; case IDs and literal full questions are not runtime rules.

### 10.4 Shadow comparison

The existing matcher remains authoritative while the semantic matcher records:

- control and shadow status;
- parsed claim/evidence atoms;
- differing slot and reason;
- unsafe disagreement classification; and
- selected evidence sentence and ground.

An unsafe disagreement is any shadow `SUPPORTED` result where the control is
`INSUFFICIENT`, `CONTRADICTED`, or `CONFLICTED` unless the case has reviewed
ground truth proving support.

### 10.5 Matcher acceptance

- all focused matcher unit and artifact replay tests pass;
- the prior false-contradiction corpus is corrected while all known real
  contradiction controls remain rejected;
- no unreviewed unsafe disagreement remains in difficult, 85-case, or full
  evaluations;
- two 85-case runs produce identical deterministic matcher decisions; and
- parser failure always maps to `INSUFFICIENT`.

## 11. Phase 5: Independent Blocking Gates

### 11.1 Named gates

Add the following independent results:

`curated`

- all non-generated, manually curated cases;
- blocks on any retrieval, result-status, or answer-verification failure.

`answerOracle`

- every case with an explicit answer oracle;
- blocks on missing proposition/condition groups, forbidden expressions,
  unsupported claims, contradiction, or failed question alignment.

`noGrounds`

- every case whose expected result includes `NO_GROUNDS`;
- blocks if the system emits a grounded affirmative answer or selects forbidden
  evidence.

Each gate reports total, passed, failed, pass rate, failure IDs, and failure
causes. Overlap between named gates is intentional.

### 11.2 Gate policy

Release mode requires zero failures in all three named gates and zero failures
in the existing full gate. A targeted developer run can select a named gate but
cannot write to reserved full-release artifact paths.

The gate manifest includes its selected profile. Resume compatibility includes
the profile and lexical revision.

### 11.3 Evaluation-set maintenance

Expand `NO_GROUNDS` beyond the current small negative set with:

- nearby-domain distractors;
- conflicting current/obsolete versions;
- title-only matches;
- related definitions without direct grounds;
- missing-condition and wrong-subject examples; and
- unsupported deadline, amount, obligation, exception, and sanction requests.

New cases are data, not runtime special cases.

## 12. Delivery Sequence

Every implementation slice follows:

1. root-cause analysis;
2. smallest generalized change;
3. focused failing test first;
4. implementation and focused pass;
5. self-review of diff and failure modes; and
6. full applicable test suite.

The planned slices are:

1. provenance manifest and independent gate data model;
2. backlog classifier and safe repair tooling;
3. explicit parent/chunk version metadata and preview audit;
4. bounded document replacement and rollback recipe;
5. common lexical schema and deterministic tokenizer;
6. BM25 query and RRF shadow trace;
7. candidate-level loss trace;
8. reusable Judge/final-selection corrections;
9. `EvidenceAtom` model, parser, and shadow matcher;
10. reviewed matcher activation; and
11. named release gates and final promotion verification.

## 13. Verification Ladder

For each slice:

- focused Java or Node tests;
- relevant artifact replay tests;
- `.\mvnw.cmd test`;
- Node evaluation/provenance/gate tests; and
- diff and runtime-state review.

For RAG behavior milestones:

1. targeted cases for the changed failure class;
2. difficult 12 cases;
3. 85 cases;
4. the same 85 cases a second time;
5. all 1,004 cases; and
6. a second deterministic retrieval replay where applicable.

A quality claim is not made when OpenAI quota, Qdrant availability, runtime
provenance, or index stability prevents the required run. Such a result is
reported as blocked or unverified, never as a pass.

## 14. Rollout and Rollback

Feature flags control:

- common lexical index read;
- RRF shadow computation;
- RRF authoritative ordering;
- candidate trace persistence;
- EvidenceAtom shadow matcher; and
- EvidenceAtom authoritative matcher.

Flags advance independently. Disabling a flag restores the previous control
path without requiring a data rollback.

Chunk rollback uses retained previous-version rows and the recorded re-index
recipe. Lexical rollback selects the previous lexical revision. Runtime rollback
uses the recorded JAR and configuration hashes. A rollback is complete only
after a new stable index revision and provenance manifest are recorded.

## 15. Non-Goals

This program does not:

- introduce Elasticsearch or OpenSearch;
- replace OpenAI embedding or answer models;
- disable or relax ClaimVerifier, AnswerGuard, or fail-closed behavior;
- rewrite unrelated admin or frontend features;
- change the 18080 batch-runner lifecycle;
- hard-code fixes for individual evaluation question IDs; or
- declare quality complete from unit tests alone.

## 16. Completion Criteria

The program is complete only when:

- provenance-stable baseline and final artifacts exist;
- active searchable embedding backlog is zero;
- chunk quality and parent-boundary acceptance criteria pass;
- common BM25 and RRF are active after shadow review;
- candidate-present Judge/selection loss meets its target without negative
  regression;
- EvidenceAtom is authoritative with no unreviewed unsafe disagreement;
- curated, answer-oracle, `NO_GROUNDS`, and full release gates all pass;
- the difficult 12 and two consecutive 85-case runs pass;
- the complete 1,004-case gate passes under one stable final manifest; and
- runtime and rollback state are documented without changing 18080.
