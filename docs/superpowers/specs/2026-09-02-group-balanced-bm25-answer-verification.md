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

## Authoritative-candidate gate

### Outcome

- Gate: `FAIL_CLOSED`
- Cases: 4/5
- Blocking case: `security-review-procedure`
- Answer-verification failures: 1
- Request errors: 0
- Qdrant search failures: 0

The other four predeclared cases passed. `security-review-procedure` retrieved
valid request and review grounds, but its verified answer omitted the required
`결과 통보` proposition. The complete procedure ground already exists in
`official_doc:84923`; it was present at vector rank 16 but did not enter the
final selected-ground set. This is a retrieval/selection coverage failure, not
an OpenAI request error or an unsupported-claim failure.

### Fixed provenance

- Git commit: `71da5bc7fa12d9925d87499730190abe40eab15a`
- Dataset SHA-256: `322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6`
- Selection SHA-256: `b765d04ea46bd6c4cacc15a5c50b97af37c794a7292ea1245c73fe767567830f`
- Runtime JAR SHA-256: `fbaba9faa4294a982d0eb46acafca21e89fa99b04dbd08a23bd988dbaddd5c87`
- Runtime config SHA-256: `6db6b2f969c26a5f0dcc1d5148e84d50a1059812cee1580a0ed69f589ffe8ef6`
- Runtime instance: `f14f0d2f-af6e-47dd-bc12-91942a1742a6`
- Index revision: `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- Law DB/Qdrant points: `211548 / 211548`
- RAG DB/Qdrant points: `84248 / 84248`

### Promotion decision

The authoritative path is not promoted. Committed defaults remain disabled,
and the conditional Difficult-12, holdout, and full 1,003-case release gates
are not launched after this blocking failure. A later iteration must recover a
complete procedure ground through a general retrieval/selection rule and start
the promotion ladder again from the targeted answer gate.

### Evidence

- `docs/evidence/rag-quality/group-balanced-bm25-authority-targeted-952bd697.json`
- `docs/evidence/rag-quality/group-balanced-bm25-authority-targeted-952bd697.md`
- `docs/evidence/rag-quality/group-balanced-bm25-authority-answer-gate-952bd697.json`
- `docs/evidence/rag-quality/group-balanced-bm25-authority-answer-gate-952bd697.md`
