# Reviewed PDF quarantine and deterministic preparation

Base: dbb1e8a7355c7a3910ee0706768168e5be586dcb. This checkpoint is not source activation, INDEX_READY, penalty eligibility or an answer-quality PASS.

## Implemented boundary

- Insert-only rag_pdf_source_drafts journal retains the reviewed PDF bytes, immutable draft/replay identities, exact baseline snapshot and product SHA in one transaction.
- Only server-owned trusted review hashes can stage or load a draft. This class is not a Spring bean or endpoint, and no request field supplies approval authority.
- A locked actual baseline snapshot is rechecked; existing documents, chunks, embeddings, recovery pointers and source metadata are not updated.
- Readback rehashes retained bytes, reconstructs draft identity, checks replay text against the reviewed literals, returns defensive byte copies and rechecks the baseline.
- A short READ_COMMITTED discovery transaction ends before the serializable document-first lock/revalidation transaction. No automatic retry masks concurrency failure.
- Preparation reads only retained bytes, extracts the entire source in capped worker batches, runs unchanged V4 chunking/quality checks and adds necessary exact reviewed units. Full extraction, retained chunks, original quality rejections and unit locators contribute to the manifest.
- Every reviewed unit retains its own heading and exact line locator in a distinct candidate row, even if its text duplicates an original chunk. Body equality is not ownership or coordinate equality. Multipage units have explicit page/normalized-line bindings and no fabricated single page number.
- Preparation is in-memory only; it inserts no searchable candidate, builds no vector/lexical assets and issues no activation receipt.

## Evidence

- Store API RED e875b5; owned MariaDB GREEN065577. Tamper/trigger rollback GREEN4f311d.
- Readback REDa0b858; GREEN85311d.
- Deterministic concurrent read/restage REDa2588f: actual MariaDB 1213 / SQLSTATE40001. Corrected lock order GREEN0b7429.
- Whole normalized transport REDaceb4c → focus8ba228, 31/f0/e0. Pinned NCSC PDF (SHA 2641449f2ce05d6871d733a5a4a9e210d996b9153a9466a74edc73a386502945) yielded exactly the same 168 pages / 126787 characters as the existing extractor (d9cac1).
- Preparation REDdca195 → own-DB GREENe02885. Final expanded own-DB f368c7 exit0 includes replaced incoming file, identical retained-source manifest, defensive readback, concurrent operations, tampered retained bytes, stale baseline, unapproved review, wrong product, and a real SQL-trigger rollback.
- All database fixtures enforced loopback23306, exact pandora_import_tx_test database and the resolved owned data directory; each successfully started test server stopped normally. No production corpus or protected listener was changed.
- Scoped independent code review: no Critical/Important findings.
- Initial approved-host backend suite97883/result7e7045: exit0, 2069 tests, failures0/errors0/skipped81. A later ownership regression was added before commit (RED41855b); final post-fix suite is recorded below when complete. The excluded DB tests are not substituted for the executed owned-DB evidence above.
- Post-fix owned-DB dec373 exit0 retains each unit's exact heading/locator independently. Final approved-host full35853/resultd138f2 exit0: 2069 tests, failures0/errors0/skipped81.
- The first non-elevated full run was infrastructure-aborted at Mockito's attach helper, not an assertion failure; the normal approved-host run passed without policy or test changes.

## Ruling and limits

Bounded PDF bytes are stored in the quarantine transaction rather than a separate filesystem artifact store. This eliminates cross-resource partial insertion and preserves existing original paths; cost is up to 8 MiB per staged source plus manifest storage, so database packet/retention configuration must be checked before real use. The owned fixture uses a 32 MiB packet bound.

Dense PDF batches can fail closed at the worker output limit. No fallback drops text or raises safety limits. Review coordinates are normalized-extraction line ordinals on physical pages, not native PDF tags or glyph geometry.

## Remaining before real source replacement

Do not bypass the old recovery validator. Existing recovery index preparation explicitly compares the generation source hash to the stored original, and existing proof lookup reads source hash from the original document row. A source-aware generation metadata/original lookup, isolated vector/lexical/corpus preparation, atomic source transition and rollback must be implemented together before activation. Current reviewed plans cannot be passed to the old same-original promotion path.

Atlas 18080, paused reservation and current app/Qdrant runtime remain unchanged. No terminal evaluation was replayed and no penalty/later checkpoint was run.
