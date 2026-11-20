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

Stable full regressions and official deploy; exact payload authorization;
fresh source/corpus/runtime binding and capability
preflight; then the existing exclusive schema5 slot and error-only attempt cap.
No ended quality FAIL is replayed. The ladder is penalty→Difficult12→holdout57→full1003
in unique slots. A new-version PASS cannot be attributed to the old oracle.
## Bound runner operations

The dedicated entry point is `node scripts/rag-eval-versioned.js`. It requires
`RAG_EVAL_CONTRACT_SHA256`, `RAG_EVAL_STAGE` (penalty, difficult,
holdout, full), `RAG_EVAL_EXECUTION_MANIFEST`, and `RAG_EVAL_PAYLOAD_APPROVAL`.
Missing authorization fails before network access or one-shot slot reservation.
Do not use legacy output/resume/selection overrides for this path.

After deploying the verified artifact with the registry still empty, run
`node scripts/capture-versioned-source-audit.js --write` with the contract hash
set. This reads three declared owners in a local READ ONLY DB snapshot and binds
them to the serving corpus fingerprints between two runtime GETs. It makes no
model call and does not register the contract.

`node scripts/prepare-versioned-execution.js approval-request SOURCE_AUDIT_REL --write`
creates an immutable payload approval request. It does not create a receipt.
Only an actual explicit grant may be recorded as a receipt with schemaVersion 1,
approvalRequestSha256, authorizationReference and approvedAt. After approved
registration through the official runtime procedure,
`node scripts/prepare-versioned-execution.js execution-manifest REQUEST_REL RECEIPT_REL --write`
binds the new instance without changing the approved questions, sources, JAR,
configuration or runner. Artifact paths are local, relative and confined to the
selected repository root. Neither preparer can activate a registry.

Each stage checks current source text, owner/version and DB/Qdrant correspondence
before reserving a unique slot and again before persisting success. Later stages
require a complete zero-blocking predecessor under the same execution identity.
Quality failure is terminal. Only actual error rows can be retried, at most twice
after the initial attempt. Full verifier envelopes and exact response contract
attestation are mandatory; source-qualified scores can only add restrictions to
legacy success, never convert a legacy failure into success. Original results
remain unchanged and new-version results must be labeled with their own contract.

Every received HTTP attempt is written once to a separate non-promoting artifact
before response attestation or end audit. A postflight failure records references
to those observations but can never promote them to a PASS. Versioned POSTs reject
redirects so questions cannot be automatically retransmitted to another endpoint.

The original approved baseline has mixed line endings in this execution checkout.
All byte-bound checks deliberately fail closed if a fresh checkout changes those
bytes; portable baseline packaging is deferred. Do not normalize the original
files or silently reseal this contract to hide a mismatch.
