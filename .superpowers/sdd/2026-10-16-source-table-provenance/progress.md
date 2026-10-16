# SDD ledger — plan: docs/superpowers/plans/2026-10-16-source-table-provenance.md

- BASE: eeeaab03. Task1 in progress; no runtime/schema/corpus change.
- Pre-flight Task1→Task2: immutable SourceTableRow cells and original XML paths required; text stays unchanged. Task2 must carry exact ranges rather than locate duplicate text by substring.
- Pre-flight Task2→Task3: nullable schemaVersion1 proof must pass source/document/version/hash/coverage validation. No legacy fallback creates proof.
- Ruling: execute inline under continuing approval; no repeated approval gate. Live migration and authority remain off until verified.
- Ruling: native Windows ledger handling uses apply_patch; no extra execution engine. Initial directory creation required approved host e0e3d4.
- Task1 initial API compilation failure34373/e79430: tableRows absent. Added immutable API scaffolding only; next run must exhibit empty-proof assertion failure before extraction implementation.
- Task1 actual RED52517/2641ec: expected4 rows, actual0. Implementation then focused87619/b46910 exit0. Expanded vertical-span/header-reset/ambiguous-header/end-caveat and nested/malformed controls58582/f13741 exit0. Full suite in progress; not complete yet.
- Ruling: Task2 must extend row metadata with exact rendered offsets while extraction still owns the builder. If cleanup changes text and no exact transform is available, discard that page's structural proof rather than relocate it by substring. Cost: conservative loss of new proof, never guessed ownership.
- Task1 verification: full81640/9ac6da exit0, XML1676 f0e0 skipped20/dec98e. Final immutable-collection assertions6609/111dd0 exit0. Actual source3b493f:2tables16rows66boundcells/unique paths/text preserved/caveat unowned. No answer-quality PASS inferred.
- Task1: complete pending commit identity. Task2 next: exact offsets, then null-compatible persistence; no live migration yet.
- Task1 commit/push1baae4ed/31d0a7. Still not deployed; no semantic authority change.
- Task2 range precondition RED95395/3d7cc4:2 failures, errors0 (unset offset and stale proof after cleanup). Added exact per-table builder offsets translated at append; any cleanup difference drops structural proof. Focused78190 in progress. Task2 persistence/association remains unimplemented.
- Task2 range precondition verified:78190/c2c4f8 focused exit0; full28992/4b2db2 exit0, fresh8804211678 f0e0 skipped20. Actual sourceaa171e exactPageRanges true,16rows retained. Task2 is NOT complete; next is offset-carrying chunk segments, not substring-based relocation.
- Range commit/push63930430. Official stage17025 exit0; official deployment39836 KEEPALIVE PID15572/runtime09874530-9fe1-4c5a-80c1-be668cc966ed/JAR39b1e616df3808ea26eab2c86ffc7d4caad0c1f65f87003e481ee51892037698. Fresh02b36b DB=Q211658+84248,readytrue/fail0/fingerprints unchanged. No schema/corpus/semantic authority change.
- Exact next step: failing chunk segmentation tests for repeated rows and2111-character split rows, then carry explicit source ranges through quality gate and import rebinding. Do not rerun Task1/old deployment verification on an unchanged SHA.
