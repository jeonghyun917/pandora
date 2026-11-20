# Candidate-bound evaluation checkpoints

User approval: explicit request and reply to call_2409bca57d884ac98400b89588acdec1/0
authorize separately verified candidate31aac7fc/JAR881f6a11… once per candidate,
preserving old failed evidence, questions, source, oracle and verifier unchanged.
The user delegated implementation choices and opted out of further design questions.

Approaches: deleting the old slot would destroy evidence and is forbidden; changing
questions/contracts merely to create a free slot would distort the benchmark.
Selected: add the immutable candidate JAR hash to the existing contract/selection
namespace, with explicit candidate-once-v1 approval policy. This is the executor's
minimal implementation choice, not a change to the question/scoring contract.

Same JAR must remain consumed across process restarts, receipt changes, runner
changes and legacy/new path transitions. A legacy started slot needs an identifiable
terminal artifact before a different candidate can proceed; missing/ambiguous
ownership fails closed. Never overwrite or read old failed answers into execution.
Predecessors must match the same candidate and entire execution identity.

New approval requests bind current JAR/source/runner plus the candidate policy.
The latest user grant covers implementing this bounded path and then evaluating
candidate31aac7fc with unchanged payloads; record that actual grant, not a fabricated
reply or a mutation of the previous approval. Keep existing legacy identities
readable without assigning them the new policy. No backend/retrieval changes or
rebuild of the already-verified candidate JAR. Preserve Atlas and paused automation.
