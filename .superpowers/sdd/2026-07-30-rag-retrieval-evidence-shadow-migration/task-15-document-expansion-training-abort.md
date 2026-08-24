# Document-expansion training abort

- Status: `TRAINING_ABORTED_CAPTURE_SCHEMA_FAILURE`
- Approved manifest:
  `4a5925f62213791998836e8739b02c2f42c9c37c995e08a40e5061eeb0923b38`
- Run 1: 24 cases attempted, 24 debug responses received, 24 approved
  OpenAI Embedding API calls consumed, 0 measurements accepted.
- Run 2: not launched.
- Selector: not launched.
- Difficult/holdout: not consumed.

Every response failed the same local validation:
`documentExpansionFused must contain at most 24 items`.

The server correctly returned a full shadow-fused ranking. Because nullable
document-expansion fields are serialized on every fused item,
`hasDocumentExpansionMetadata` classified the complete fused ranking as
expansion-source hits and incorrectly applied the 24 source-hit ceiling to it.
This is a deterministic evaluator capture-schema defect, not a request, OpenAI,
Qdrant, MariaDB, or runtime failure.

Post-failure runtime identity remained unchanged. Law DB/Qdrant parity is
`211548/211548`, RAG parity `84248/84248`, Qdrant is ready with failures `0`,
both collections remain green/optimizer `ok`/queue `0`, and 18080 is absent.
All authority flags remain false.

Fail-closed action: no retry, no second run, and no selector. The evaluator
must be fixed and independently verified, then a new JAR, new immutable
manifest, and new exact approval are required.
