# Qualified example scoring (offline candidate)

The module scripts/lib/rag-qualified-example-scorer.js contains deterministic,
fail-closed checks for already verified conditional examples. It does not
register a server contract, make requests, activate a dataset, or replace the
legacy release gate. An offline acceptance is not a penalty PASS.

## Inputs and required proof

The input is the complete verifier envelope: verified answer, boolean alignment
and insufficiency, all claim-evidence links and their source attributions,
unsupported/numeric/contradicted claims, and unsafe semantic shadow count.
Truncated reports and model-authored self-verification are not valid inputs.

The separately reviewed contract must bind exact source document/chunk owners
and literal clauses. Before any live use, the runner must additionally establish
current source edition, corpus and runtime identity and payload authorization.
This module alone cannot establish freshness or the question's applicability.

- Qualified approval: exactly one applicability clause and one operative remedy.
  They must have different chunk owners in the same document and target.
  The applicability clause may have its exact reviewed heading; the remedy must
  retain its actor, scope, trigger, action and modality. Every answer claim must
  be accounted for and independently attributed.
- Assumed consultation: exactly three scope clauses (inclusion, exclusion,
  proviso) and the separately owned remedy, in that order. The scope clauses use
  one source number; the remedy uses a different number in the same document.
  The assumption is not evidence that an unspecified real project is covered.

Both modes require successful verifier alignment, no insufficient-evidence
verdict, no unsupported, numeric-unsupported or contradicted claims, and no
unsafe semantic disagreement. Missing or ambiguous attribution fails closed.
Extra answer assertions and incomplete qualifications are rejected.

## Verification

Run the following command in the worktree:

    node --test scripts/rag-qualified-example-scorer.test.js scripts/rag-assumed-consultation-scorer.test.js scripts/rag-eval-provenance.test.js scripts/rag-retrieval-eval.test.js

The HTTP tests use local synthetic servers, not Pandora's live model endpoint.
Use the approved host if a restricted child process cannot reach its local test
server. Do not reinterpret transport failures as an evaluation result or relax
the gate to make them pass.

The current server continues rejecting every unregistered versioned evaluation
contract. Live evaluation requires explicit server/runner integration and its
own verified contract identity. The frozen dataset, old checkpoints, original
oracle and release-blocking gates remain unchanged.

## Trusted evaluation transport

Evaluation case reports now append `verificationEnvelope` when the real
claim-and-alignment verifier executed. Schema1 carries the untruncated verified
answer, independent alignment/insufficiency, unsupported/numeric/contradicted
claims, links and unique owner attributions, unsafe disagreement count, and an
unambiguous source-bound reference receipt when present. No oracle verdict is
included. Skipped, metadata-only and generation-error paths leave it null.
Unevaluated claim-only alignment is not reported as evaluated alignment.

Legacy `passed`, `answerVerified` and the1500-character-plus-ellipsis display
remain unchanged. New source-qualified scorers must consume the nested complete
envelope, never infer verification from those legacy combined/truncated fields.
Adding this transport registers no contract and authorizes no external payload.

## Reference-qualified general commencement candidate

`scripts/lib/rag-reference-commencement-scorer.js` is a separate offline subtype,
not a replacement for the original future-status oracle. It consumes the full
server verifier envelope plus the server-bound official effective-phase receipt.
The reviewed contract fixes the law title/ID/edition, source owner/version,
general commencement date and explicit reference date. Both separately supported
date/status facts must be present and independently attributed to that owner;
the entire answer must be accounted for. Missing receipt, wrong owner/version,
wrong year/date, partial or additional claims, unsafe disagreement or failed
verification rejects the candidate. It never infers all-provision status.

The receipt must come from the trusted server's source-owned resolver, not a
model, client-authored assertion or a truncated legacy report. HTTP runtime,
contract and corpus provenance still need their own preflight before live use.
The scorer itself makes no request and registers no live contract.

RED `31d779` → focused GREEN `e9bd9b`; complete Node regression `b11058`:
208 tests, zero failures/skips. Model-free staged-product/source integration
`711841` accepts two supported claims, unsupported0, with full envelope hash
`6f1fb448d3b6676b3c8b80e3453079d36ebdf9d6d4a1a2f7a545e37543fdba84`.
This is not a live quality PASS. No serving JAR change is needed for this
offline-only scorer.

## Contract availability preflight

`GET /api/law-data/ai/debug/evaluation-contracts` is read-only and does not enter
retrieval, generation or evaluation. Its schema-1 response reports the exact
contract header and registered SHA-256 values. The list is currently empty,
consistent with the existing 422 fence; exposing this endpoint activates no
scorer or dataset.

`scripts/lib/rag-evaluation-contract-preflight.js` makes one GET to that server
origin, with no case/question payload, credentials, redirects or retries. It
requires a canonical selected hash, the expected schema/header, and unique
canonical registered hashes containing the selected hash. An absent/unknown
contract, malformed response or HTTP error fails before a future caller may
reserve an evaluation slot. It does not itself reserve a slot or enable a new
runner mode. The runtime/corpus and exact external-payload preflights remain
required before the existing one-shot reservation and any evaluation request.

RED `497ef1/c29b5c` → focused `147d9d/dcc592`. Complete Node `891be7/b57fb6`
passes 211/fail0; complete backend `1e27ee/2ab433` exits 0. Existing malformed
contract 400, unknown contract 422 and legacy gate behavior are retained.
