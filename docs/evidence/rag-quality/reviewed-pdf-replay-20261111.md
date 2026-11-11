# Reviewed PDF offline preparation

Base: 0c9566fec4729c4124f38d46fd46416e848ecec3. This is preparation code, not a source activation or RAG quality PASS.

## Contract

ReviewedPdfDraft binds the document ID, preserved-baseline hash, new original hash, product SHA, title, source URL and ordered unit keys/headings/review digests. ReviewedPdfUnit binds physical page and normalized-extraction line ordinals plus exact literal values. These are not raw glyph geometry, native PDF structure tags, or legal-role approval.

Pinned replay calls the existing memory/deadline/output-capped SourcePdfWorker. The worker hashes the same bytes it parses and returns only the requested interval. Parent validation requires the exact hash, page, interval, cardinality and literal lines. No PDF parser is invoked inside the replay caller. The expected draft hash must come from a trusted server-owned review record; a caller computing its own hash has not gained review authority.

No endpoint, database table, searchable candidate, vector write, promotion permission or HWPX-validator exception is added.

## Verification

- Missing-interface RED checks: a4132f (Path replay), 9e4111 (draft binding).
- Focused: f94eff, 30 tests, failures/errors 0. Additional real draft replay assertion included in the final full suite.
- Stable full backend: session 59685, result 330f4e, exit 0; 2067 tests, failures 0, errors 0, skipped 80. The excluded tests are not claimed as executed integration coverage.
- Independent scoped review: no Critical/Important issue; coordinate terminology clarified.
- Local original: NCSC PDF SHA-256 2641449f2ce05d6871d733a5a4a9e210d996b9153a9466a74edc73a386502945. Seven independently reviewed units, 116 normalized-extraction lines replayed through the capped worker (82572c). The original is not registered or transmitted to a model.

## Remaining

Persist trusted review identity separately from submitted drafts; materialize a new-original inactive version without overwriting the preserved original; carry multi-page locators into serving; bind complete source regeneration and reference roles; verify namespace/corpus isolation and source-aware atomic promotion/rollback. The existing same-original recovery API must not be weakened to substitute for a replacement-source workflow.

Penalty applicability remains separate and unresolved. No release-ladder checkpoint was run. Atlas 18080, app-dev runtime, Qdrant and the paused reservation were not changed by this work.
