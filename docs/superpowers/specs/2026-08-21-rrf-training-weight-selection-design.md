# RRF Training and Weight Selection Design

## 1. Goal

Select one deterministic, general-purpose vector/BM25 reciprocal-rank-fusion
(RRF) weight pair without tuning on the difficult-12 acceptance set or the
remaining holdout cases. The selected pair remains shadow-only until every
existing Task 15 acceptance gate passes.

## 2. Scope

This slice adds:

- an immutable, reviewed 24-case training manifest;
- a bounded rank-capture mode for the existing retrieval evaluator;
- an offline RRF weight selector with deterministic tie-breaking;
- provenance and rank-repeatability checks across two training captures; and
- a recommendation artifact that can be reviewed before runtime configuration
  changes.

It does not change the RRF formula, add document-specific boosts, add
question-specific rules, alter the lexical index, activate authoritative RRF,
or touch the `18080` batch runner.

## 3. Dataset Separation

The training manifest contains exactly 24 explicit-oracle cases selected by
metadata strata (intent/section type and source-target mix), not by observed
difficult-12 outcomes. It includes target-scope, procedure, exception,
requirement, contract, definition, law-only, and mixed-source examples.

All 12 difficult case IDs are explicitly excluded, including the four without
an answer oracle. Every explicit-oracle case that is neither training nor
difficult is holdout. `NO_GROUNDS` controls remain separate safety gates and
are never used to maximize retrieval recall.

The ordered training IDs are:

1. `project-review-target`
2. `project-review-simple-software`
3. `project-review-hardware-exclusion`
4. `pre-consultation-target`
5. `pre-consultation-when`
6. `pre-consultation-exception`
7. `security-review-target`
8. `security-review-exception`
9. `security-review-procedure`
10. `rfp-required-items`
11. `rfp-tech-score-table`
12. `public-data-db-standard`
13. `procurement-catalog-contract`
14. `commercial-sw-direct-purchase`
15. `irm-faithfulness`
16. `traffic-crosswalk-stop`
17. `video-cctv-guide`
18. `personal-info-purpose`
19. `privacy-consent-notice-items`
20. `mois-autonomy-preconsultation-procedure`
21. `pipc-cctv-public-place-exception`
22. `pipc-pseudonym-additional-info`
23. `msit-tving-investigation`
24. `ai-law-enforcement-date`

The manifest is JSON with schema version, split name, ordered training IDs,
excluded difficult IDs, selection basis, and expected case count. Its SHA-256
is computed from the exact bytes and recorded in every capture and selection
artifact.

## 4. Rank Capture

The existing `scripts/rag-retrieval-eval.js` gains an opt-in
`RAG_RETRIEVAL_CAPTURE_RANK_LIMIT` setting. Default `0` preserves current
artifacts. A positive value up to `100` records, per case:

- ordered vector candidate keys and ranks;
- ordered BM25 candidate keys and ranks;
- each candidate's server-confirmed `matchedAuditGroupIndexes`; and
- no chunk body, snippet, or question text beyond the evaluator's existing
  case metadata.

Training is captured twice with limit `100`, K `30`, concurrency `1`, the same
ordered case IDs, and one stable runtime/JAR/config/index/lexical manifest.
Each question therefore makes one normal embedding request per capture. Any
request error or runtime/index drift invalidates the capture.

## 5. Offline Selection

The selector consumes exactly two completed training artifacts and the exact
manifest. It rejects:

- manifest, dataset, selection, runtime, JAR, config, index, or lexical hash
  mismatch;
- missing, duplicate, extra, or reordered training cases;
- absent explicit oracle groups;
- incomplete vector/BM25 rank snapshots; or
- any candidate-key, rank, or matched-group difference between captures.

RRF remains:

`score(d) = vectorWeight / (60 + vectorRank(d)) + lexicalWeight / (60 + lexicalRank(d))`

The predeclared candidate grid is:

- `(1.0, 0.5)`
- `(1.0, 0.75)`
- `(1.0, 1.0)` baseline
- `(0.75, 1.0)`
- `(0.5, 1.0)`

Candidates sort by descending RRF score, ascending best source rank, target,
then numeric chunk ID, matching production semantics. Metrics use the first 30
candidates.

## 6. Selection Rules

A non-baseline pair is eligible only when:

1. it improves training all-required top-30 count over `(1.0, 1.0)`;
2. every case that passes all-required under baseline still passes;
3. it has no reduction in any-required count; and
4. both captures produce identical fused ranks and metrics.

Eligible pairs sort by:

1. highest all-required count;
2. highest any-required count;
3. highest total matched-group count;
4. smallest absolute log weight ratio from baseline; and
5. vector weight then lexical weight numerically.

If no non-baseline pair is eligible, the output explicitly recommends the
baseline and reports `NO_TRAINING_IMPROVEMENT`. The selector never edits
runtime configuration.

## 7. Promotion Ladder

After a non-baseline recommendation:

1. review and commit the recommendation artifact;
2. change only the two RRF configuration values;
3. run focused unit and Node tests, then the full backend suite;
4. promote the verified JAR only to app-dev `8080` using documented scripts;
5. verify runtime/JAR/config/index/lexical provenance and Qdrant health;
6. run difficult-12 twice with identical rankings;
7. require at least 80% explicit-oracle all-required top-30 recall, zero
   false-ground regression, zero request errors, and warm p95 at most 500 ms;
8. run the untouched explicit-oracle holdout twice; and
9. proceed through the existing 85-case and full release gates.

RRF and semantic authoritative flags remain false until the full existing
promotion ladder passes. A failure restores the last verified configuration;
it does not trigger further tuning on difficult or holdout results.

## 8. Safety and Operational Constraints

- Never write candidate text or secrets to the manifest or selector artifact.
- Never consume `NO_GROUNDS`, difficult, or holdout outcomes as optimizer
  inputs.
- Never start, stop, restart, or modify port `18080`.
- Never read or modify the untracked `output/` directory.
- Use app-dev `8080`, Qdrant `6333`, and the documented runtime scripts only.
- Fail closed on external API, Qdrant, provenance, rank-stability, or artifact
  ambiguity.
