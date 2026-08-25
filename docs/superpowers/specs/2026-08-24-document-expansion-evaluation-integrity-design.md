# Document Expansion Evaluation Integrity Design

## Goal

Make document-expansion evaluation compare like-for-like fused rankings, preserve structured expansion outcomes, and recover strict multi-token Korean document-title variants without weakening the strong-anchor gate.

## Approved behavior

- Compare the top-K control RRF list (`fused`) with the top-K document-expansion RRF list (`documentExpansionFused`).
- Keep candidate-source and expansion-source presence as diagnostics; do not use their union as the promotion control.
- A missing expansion contribution is not a drop. Report `documentExpansionFused` only when a control-fused required group is actually lost.
- Expose `documentExpansionStatus` and bounded `documentExpansionReasonCodes` as structured debug fields and persist them in evaluation results.
- Split a multi-word explicit Korean title into ordered, distinct title terms. Existing SQL and Java selection must continue to require every term, so generic topic questions remain `NO_STRONG_ANCHOR`.
- Keep document expansion non-authoritative and do not run external evaluation as part of implementation verification.

## Safety constraints

- No question-specific ID, document ID, oracle term, or production allowlist.
- No OpenAI, Qdrant, MariaDB mutation, or port 18080 action.
- Preserve existing bounds of three documents, eight chunks per document, and 24 chunks globally.
- Old captures without the new fused-control/status contract fail closed rather than being promoted.
- Runtime document expansion remains shadow-only until a compatible post-change evaluation proves improvement.
