# Group-balanced BM25 Difficult-12 verification

## Outcome

- Gate: `GATE_PASS`
- Evaluated cases: 12/12 in each of two independent runs
- Request errors: 0 in both runs
- Control-group regressions: 0
- Nondeterministic captures: 0
- Qdrant search failures: 0

## Fixed provenance

- Git commit: `de8c28cbcd8dbc5002ca571b965e768d14877a42`
- Difficult manifest SHA-256: `599e39fc4d3b82a1c9c996ba982f0856b0f4279b1f9de3cbbd9e211d6f2e3b78`
- Dataset SHA-256: `322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6`
- Runtime JAR SHA-256: `84d029471157a445a2a30fef106d86a47044a208c5e1731c9943e4766abaa02e`
- Runtime config SHA-256: `e7b08ced10e7fd56f1dbfda7822dccb019aec056cf31ea16fca24604cbd1576a`
- Runtime instance: `82ae43b3-b163-4311-afe4-d845a4f8b8dd`
- Index revision: `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`

## Metrics

Both runs produced the same values. Across the eight cases with explicit answer groups, control and shadow each had 6 all-required cases, 8 any-required cases, and 17 matched groups. The four retrieval-only cases were retained for deterministic candidate-order checks but were not counted as answer-group successes.

## Evidence

- `docs/evidence/rag-quality/group-balanced-bm25-difficult-run-1-de8c28cb.json`
- `docs/evidence/rag-quality/group-balanced-bm25-difficult-run-2-de8c28cb.json`
- `docs/evidence/rag-quality/group-balanced-bm25-difficult-gate-de8c28cb.json`

The selected training policy remains shadow-only. This gate proves no measured Difficult-12 regression; it does not authorize production authority.
