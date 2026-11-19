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
