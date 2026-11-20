# Inactive versioned evaluation integration

The original TSVs, oracle, results and legacy evaluation mode are unchanged.
The separate proposal changes only two explicit request/rubric identities;
see `docs/evidence/rag-quality/evaluation-versions/README.md`.

## Exact catalogue and default-off registry

`node scripts/seal-rag-evaluation-catalogue.js` prints the proposed catalogue
SHA and size without writing. Optional `--write` creates only its hash-named
classpath JSON resource and refuses conflicting existing bytes. The JSON resource
is marked `-text` in Git: its exact byte hash must survive Windows checkouts.
No approval receipt, registration property or HTTP request is created.

`EvaluationContractRegistry` is empty by default even when the resource exists.
Only explicit operator startup selection using
`pandora.evaluation.contract-sha256` can load one classpath resource. Do not set
this property until the exact immutable payload is authorized. Missing, changed,
oversized, incomplete or malformed resources fail startup rather than falling
back to an unbound evaluation. Resource selection is not transmission approval.

## Server request boundary

For a registered `X-Pandora-Evaluation-Contract-SHA256`, ordinary
`POST /api/law-data/ai/debug/evaluate` requires explicit cases matching the full
catalogue records, unique and in original order. Questions, targets, expected
terms, oracle groups and all other case fields are bound before retrieval.
`caseIds` shortcuts, `maxCases`, unknown/duplicate/reordered/changed records
are rejected. Successful responses attest the same hash in a response header.
Malformed hashes remain400; unknown or mismatched contracts/requests remain422.

Tagged `/debug/evaluate/gate` is deliberately unavailable: its legacy verdict
must not masquerade as the separate source-qualified contract. The versioned
runner must additionally score the complete nested verification envelope and
preserve every legacy case gate. Capability GET is read-only; its registered
list remains empty unless explicitly configured.

## Still required before live evaluation

Complete runner binding and tests, stable full regressions and official deploy;
exact payload authorization; fresh source/corpus/runtime binding and capability
preflight; then the existing exclusive schema5 slot and error-only attempt cap.
No ended quality FAIL is replayed. The ladder is penalty→Difficult12→holdout57→full1003
in unique slots. A new-version PASS cannot be attributed to the old oracle.
