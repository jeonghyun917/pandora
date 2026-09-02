# Group-balanced BM25 shadow answer verification

## Outcome

- Gate: `PASS`
- Cases: 3/3
- Answer-verification failures: 0
- Unsupported-claim cases: 0
- Blocking failures: 0

The evaluated cases were `rfp-required-items`, `pre-consultation-when`, and `pre-consultation-central-agency`.

## Fixed provenance

- Git commit: `91e0098ed95f544515f60b3b5672722a52a8e9b2`
- Dataset SHA-256: `322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6`
- Selection SHA-256: `83ece68e065c49d369533a9982893433e933aa0497e3137d77695cf31fd78809`
- Runtime JAR SHA-256: `84d029471157a445a2a30fef106d86a47044a208c5e1731c9943e4766abaa02e`
- Runtime config SHA-256: `e7b08ced10e7fd56f1dbfda7822dccb019aec056cf31ea16fca24604cbd1576a`
- Runtime instance: `82ae43b3-b163-4311-afe4-d845a4f8b8dd`
- Index revision: `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- Qdrant search failures: 0

## Interpretation

This verifies that observing the selected policy in shadow mode did not regress the three predeclared answer cases. It does not yet prove authoritative answer improvement. The next resumable step is TDD implementation of the authoritative candidate path, with committed defaults remaining disabled, followed by evaluation on the exact candidate artifact.

## Evidence

- `docs/evidence/rag-quality/group-balanced-bm25-answer-gate-91e0098e.json`
- `docs/evidence/rag-quality/group-balanced-bm25-answer-gate-91e0098e.md`
