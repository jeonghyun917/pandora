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
