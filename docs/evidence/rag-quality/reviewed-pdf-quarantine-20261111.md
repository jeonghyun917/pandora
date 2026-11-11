# Reviewed PDF quarantine and deterministic preparation

Latest original-serving verification: full95906/b4242d exit0, 2076/f0/e0/skip81; actual isolated DB plus controller89814/b6f8d9 passed8/f0/e0 separately. Official staged JAR0d367f1dcb136cea107c9cfcd938ac8bf44469b2061710cb2a01811231cd4ee5. Scoped review's two Important issues (same-original recovery generation matching and preview identity) were reproduced and fixed with tests; no other C/I reported. Existing preserved-file storage behavior is not relabeled as newly rehashed source evidence. Source-specific indexing/materialization, readiness and atomic transition/rollback remain required before any real replacement.

Latest partial Task3 verification: post-fix full66412/565bc3 exit0, 2073/f0/e0/skip81; actual isolated DB92933/5f5d64 exit0 separately. Staged JAR5419d08f722467f0fdbb9c74fe8f96a7d1b35d644cd1d4b3a8050242bc1a9733; official DryRun d6f530 passed. Source-aware original serving, readiness, activation and rollback remain unfinished. No live source update or penalty was executed.

Original-serving follow-up (uncommitted): actual retained-source reader RED693e58→DB9c32bf; published-generation open RED177081→DBd48c10. Controller integration RED0f9ce7→focus0c35af6PASS, then review identified same-original non-baseline generations and preview pinning gaps. Clean actual DB REDfc3b03 confirmed the former; generation-specific PDF/HTML/asset API REDb2b34f confirmed missing routes. Combined final focus89814/b6f8d9 passed8/f0/e0 (real DB1 plus controller7), including exact source/hash/metadata, RETIRED read, PENDING/wrong-document/refused byte mutation, pinned preview assets, unchanged legacy store use, login required and no provider-message exposure. Test-owned URL stream cleanup was corrected after Windows temp-file cleanup error71c299; no production policy changed. Full95906 is running; stage68918/5b1c91 exit0, JAR0d367f1dcb136cea107c9cfcd938ac8bf44469b2061710cb2a01811231cd4ee5. New reader is not yet the live product.

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

### Task 3 partial foundation (working tree after 1ecc927)

An immutable `RecoveryPdfSource` is captured in the routing transaction and contributes to cache identity. Routed proof/title/body/context queries project the captured source hash/title/URL, not the preserved original's metadata. Unproven issuer/date/path values are cleared. The reader rejects a replacement hash without a published binding and validates generation, draft, source, product, baseline, manifest and metadata receipt together. Old captured snapshots retain the original metadata.

`RagPdfGenerationStore` separately persists fully regenerated reviewed chunks, complete preparation JSON (including multipage line positions) and their immutable source binding in one SERIALIZABLE transaction. It requires existing preserved-baseline routing, writes only inactive chunks and a PENDING generation, and checks exact reuse rather than overwriting. This class is deliberately not a bean or endpoint. No embeddings, readiness receipt, pointer switch or serving authority is created.

Focused routing tests: missing wiring RED6a2720 → GREENd26299, 9/f0/e0. Actual owned DB source-projection RED34d8ff → GREENae9344; binding reader REDd76da5 → GREEN49409a; new persistence API REDa88bc3 → GREENfcebde. Expanded locator-tamper and real SQL-trigger rollback controls passed7479d5. Independent scoped review found the filtered-candidate query could hide wrong-document extra membership: actual DB RED42b421 reproduced silent reuse; complete membership-count verification now rejects it, GREEN5f5d64/92933 exit0 (including real SQL-trigger rollback). The owned DB stopped normally. Stable full backend run96862 is in progress; this is not a full-suite PASS claim.

Initial full96862/a49253 completed with 2073/f0/e1/skip81: the existing SQL test's partial routing Map lacked the newly required `pdfSources`. The fixture now supplies an empty map; the nonnull production invariant was not weakened. Final focus50423/e2e09f passed20/f0/e0; post-fix full66412 is running. Official staged package98131/c611f9 passed, JAR SHA256 `5419d08f722467f0fdbb9c74fe8f96a7d1b35d644cd1d4b3a8050242bc1a9733`. Production code is unchanged since this package; the subsequent change is test fixture data only. Official deployment DryRun d6f530 passed with no8080 listener.

Approved-host official statusbad2b7 confirmed8080/6333 absent and Atlas18080PID108368. After an additional no-process/no-listener guard, official start-qdrant.cmd55686f started Qdrant; read-only healthd6faa1 confirms6333PID8976, law_chunks green211658 and rag_chunks_v4 green84248. Atlas remains108368. No Qdrant process was stopped and no index was rewritten.

The first active-original membership mutation was blocked by the existing global unique-chunk constraint; the corrected regression uses an otherwise unowned foreign-document chunk, which does not alter the protected document baseline. A one-off MariaDB initialization failed with Windows sharing error32 before starting a server; approved-host checks confirmed no23306 listener and exclusive access to that owned ini before a fresh fixture. No execution policy was changed.

Still required: source-owned original/download/materialization and detail metadata, internal index-router wiring, full source-aware vector/lexical/corpus readiness, atomic promotion and rollback, actual isolated Qdrant verification and stable delivery. The old same-original index/promotion guards remain unchanged, so a PENDING PDF generation cannot be passed through them as if it were the preserved original.

Do not bypass the old recovery validator. Existing recovery index preparation explicitly compares the generation source hash to the stored original, and existing proof lookup reads source hash from the original document row. A source-aware generation metadata/original lookup, isolated vector/lexical/corpus preparation, atomic source transition and rollback must be implemented together before activation. Current reviewed plans cannot be passed to the old same-original promotion path.

Atlas 18080, paused reservation and current app/Qdrant runtime remain unchanged. No terminal evaluation was replayed and no penalty/later checkpoint was run.
