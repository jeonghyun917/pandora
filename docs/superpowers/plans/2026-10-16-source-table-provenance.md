# Source-owned table provenance implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline. Do not delegate without a substantial independent workstream. Checkboxes track verified deliverables, not elapsed time.

**Goal:** Preserve original HWPX header/cell ownership through chunk boundaries without inferring business scope or weakening claim verification.

**Architecture:** Carry typed source coordinates beside unchanged rendered text. Persist provenance separately from `source_path`; consumers accept only complete, hash-matching row bindings. Missing, ambiguous or partial bindings remain insufficient. This extends the existing pipeline; it does not replace retrieval or the verifier.

**Tech stack:** Existing Java17/Spring/MyBatis/MariaDB/JUnit stack, no new dependencies.

**Spec:** The approved task and constraints in `../../evidence/rag-quality/source-list-scope-20260929.md`, especially diagnostic252012 (one 2111-character row split into two chunks), and the existing RAG quality contract.

## Global constraints

- Feature worktree only; shared main,18080,Qdrant and output stay unchanged.
- Keep literal source text, all conditions and all existing subject/issuer/SW/modality guards.
- Do not reconstruct structure by parsing pipes/colons in rendered text.
- No source_path metadata packing, new collection/version, corpus-wide forced reimport, or authority promotion.
- Legacy data without provenance remains usable under existing guards but gains no new relationship support.
- A table caption, adjacent paragraph or document-ending caveat does not become row-owned by proximity.
- SNS+penalty actual PASS precedes Difficult12→holdout57→full1003; no replay of terminal quality failures.

## Review focus

1. Equal text in different rows/tables must never share identities.
2. Vertical spans apply only within the declared row interval; horizontal/ambiguous header layouts do not synthesize ownership.
3. Invalid/nested tables retain raw fallback and no structured proof.
4. Chunk overlap, trimming or repeated text cannot manufacture a complete row.
5. Stale source/hash/version or failed import cannot activate new proof or remove the old searchable version.

## Task1: Source-coordinate extraction, no answer behavior change

Files: `rag/importing/ExtractedPage.java`, new `rag/importing/SourceTableRow.java`, `rag/importing/RagTextExtractor.java`, `RagTextExtractorTests.java` (under existing Java roots).

Interfaces:
- `SourceTableRow(String sourcePart, String tablePath, int rowIndex, String renderedText, List<Cell> cells, int textStart, int textEnd)`; offsets are page-text UTF-16 indices, end-exclusive. The five-argument compatibility constructor supplies -1/-1 and is not valid range proof.
- Nested `Cell(int sourceRow, int sourceColumn, int rowSpan, int columnSpan, String text, String headerPath, String headerText)`; absent header is null, not an inferred label.
- `ExtractedPage(Integer pageNo,String text,List<SourceTableRow> tableRows)` with existing two-argument constructor delegating to an empty immutable list.

- [x] Add fixtures asserting separate table/row identities for duplicate text, exact original cell coordinates/spans, explicit headers, and empty structured proof for malformed/nested tables. Existing numbered-header text fixtures remain; richer numbered provenance assertions belong to Task2 coverage.
- [x] Run focused tests and observe missing contract/reproduction failure before implementation.
- [x] Reuse the existing validated grid and header ownership decisions; collect rows only after the whole table validates. Identify tables by original XML element path within the ZIP part, not rendered-text matching. Keep the existing rendered text identical.
- [x] Verify immutable collections, old constructor compatibility and existing text fixtures; full1676 f0e0 skipped20, focused immutable assertions exit0. Commit only verified files.

## Task2: Exact chunk association and transactional persistence

Files: `RagChunker.java`, `RagDocumentChunkRow.java`, import rebind, `RagDocumentMapper.xml`, `schema.sql`, isolated transaction fixtures. Add a dedicated small provenance value/codec class beside rag document types rather than growing the matcher.

Contract:
- JSON schemaVersion1, original file SHA256, sourcePart/tablePath/rowIndex, cell coordinates/header paths, rendered-row hash, and exact covered text ranges.
- Dedicated nullable `source_provenance LONGTEXT`; never insert JSON into source_path. Legacy null defaults to no new proof.
- A split row records partial coverage. A consumer may join only matching source-file hash, document/version, table path and row identity, with verified coverage of every required cell and owned condition. Text overlap is not identity.
- Extraction now supplies exact row offsets. If normalization changes the rendered page, its structural proof is dropped until an exact transformation exists; never recover positions by searching repeated text.

- [x] Test the2111-character row, repeated row text, punctuation/whitespace, row spanning multiple chunks and adjacent unrelated tables. Assert both no lost source identity and no false complete-row status.
- [x] Implement offset-carrying chunk segments; do not relocate using first/last substring occurrence. Keep ordinary text chunking and max length unchanged.
- [x] Add nullable persistence and mapper round-trip tests in the fenced isolated MariaDB instance, including pre-existing null rows.
- [x] Extend rollback tests so provenance and chunks commit/rollback together. Preserve old Q points until commit and delete only attempted new IDs on confirmed rollback.
- [x] Review migration/data-safety separately before any live schema/data mutation. No automatic corpus reimport or bulk activation follows code deployment.

## Task3: Source-linked proof consumer and guarded evaluation

Files: `LawSemanticChunkRow.java`, retrieval mapper projections, `EvidenceAtom`/indexed evidence boundary, and a small dedicated source-table binding validator. Existing final guards remain authoritative.

- [x] Test stale file/hash/version, wrong table/row, changed date, removed condition, alternate institution, missing cell coverage, delimiter-containing cell text, and malformed provenance. All must remain insufficient.
- [x] Validate and expose literal `(header source cell,value source cell)` facts with their provenance. Do not assign an institution subject or a natural-language predicate merely from a label string.
- [ ] Establish any supported natural-language relation with generalized source-owned semantics and positive/negative fixtures before enabling it. Keep unsupported or ambiguous mappings insufficient; do not add question/case-ID rules.
- [ ] Verify actual source consumers separately from synthetic tests. Re-check live runtime/quota/network/DB-Q identity before external evaluation or document-specific repair.
- [ ] Only after direct question scope and condition support is established: create one new penalty checkpoint; on actual PASS proceed Difficult12→holdout57→full1003 and all release-blocking gates, then approved final promotion/deployment/health/handoff.

## Current execution point

Task2 bounded implementation is verified and deployed in product87e2ef8a (see ledger/evidence). Task3 literal source-row/header validation and selected-ID retrieval propagation are deployed in f2e29230; 652aefb5 carries verified bindings into the real semantic EvidenceIndex without creating atoms or changing support. Generalized natural-language relation consumption, actual stored-corpus proof application and qualified external gates remain unfinished. The audited table labels do not themselves prove an institution's duty, the ownership of a document-ending caveat, or the original penalty question's general business scope. New support must not be enabled until those source-owned premises exist. Independent candidate diagnostics must distinguish ACTIVE preview counts from CANDIDATE/RETIRED and point existence from semantic release authorization. Use fresh runtime evidence before external evaluation; this plan is not identity proof. Do not rerun already-passing unchanged-SHA gates solely to create activity.
