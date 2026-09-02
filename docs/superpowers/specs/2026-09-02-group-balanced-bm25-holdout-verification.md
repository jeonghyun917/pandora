# Group-balanced BM25 independent holdout verification

## Outcome

- Gate: `GATE_PASS`
- Evaluated cases: 57/57 in each of two independent runs
- Request errors: 0 in both runs
- Control-group regressions: 0
- Nondeterministic captures: 0
- Qdrant search failures: 0

## Fixed provenance

- Git commit: `a72fdc63`
- Holdout manifest SHA-256: `70a5057f4db3ba1c909a6de715382e90982e68e54d7b8eaaac376f7178557acc`
- Dataset SHA-256: `322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6`
- Runtime JAR SHA-256: `84d029471157a445a2a30fef106d86a47044a208c5e1731c9943e4766abaa02e`
- Runtime config SHA-256: `e7b08ced10e7fd56f1dbfda7822dccb019aec056cf31ea16fca24604cbd1576a`
- Runtime instance: `82ae43b3-b163-4311-afe4-d845a4f8b8dd`
- Index revision: `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`

## Metrics

Both runs produced identical control and shadow metrics: 10 all-required cases, 18 any-required cases, and 42 matched groups across 57 independent explicit-oracle cases. The shadow added no holdout groups and lost no control groups.

## Interpretation

The training improvement generalized as a no-regression result, not as a measurable holdout gain. This is sufficient to continue to answer-quality verification, but not by itself to grant production authority.

## Evidence

- `docs/evidence/rag-quality/group-balanced-bm25-holdout-run-1-a72fdc63.json`
- `docs/evidence/rag-quality/group-balanced-bm25-holdout-run-2-a72fdc63.json`
- `docs/evidence/rag-quality/group-balanced-bm25-holdout-gate-a72fdc63.json`
