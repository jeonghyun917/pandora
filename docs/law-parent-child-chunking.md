# Law Parent-Child Chunking Plan

## Current Finding

- `law_chunks` and DB are now consistent: `law_chunks` delta is `0`.
- `rag_chunks_v4` is also consistent: delta is `0`.
- The biggest law/admrul quality issue is not only long chunks. It is line-level over-fragmentation.
- Current law/admrul chunks include many standalone fragments such as `<개정`, `2025.10.1>`, `다.`, and table border lines.
- These fragments can become independent vectors and pollute semantic search candidates.

## Stage 2 Decision

Use a parent-child style policy for future law/admrul chunk generation.

- Parent unit: provision/article/section/source path group.
- Child unit: semantic search chunk, usually <= 2,500 chars.
- Very short line fragments are merged inside the same parent.
- Long parent text is split into search-sized children with small overlap.
- Existing DB schema is not changed yet.

This gives a safer first step than a full table migration because it improves future sync and rechunk behavior without immediately rewriting millions of current vectors.

## Implemented In This Stage

- Added `LawSemanticChunkPlanner`.
- Connected `LawDocumentWriter.replaceChunks` to use the planner.
- Fixed fallback JSON path preservation in `LawOpenApiPayloadParser`.
- Populated law chunk `parentSectionTitle` and `sectionType` in mapper reads instead of returning `NULL`.
- Added regression tests for:
  - preserving nested source path
  - merging law line fragments under the same provision
  - splitting long administrative-rule text into <= 2,500 char child chunks
- Added diagnostics:
  - `scripts/search-quality-diagnostics.js`
  - `scripts/qdrant-stale-point-audit.js`
  - `scripts/law-parent-child-chunk-audit.js`

## Rechunk / Reindex Gate

Do not full-rechunk all law/admrul immediately. First validate with a limited sample:

1. Pick 10 law documents and 10 admrul documents:
   - high duplicate/noise documents
   - long parent candidates
   - normal short documents
2. Rechunk into a scratch or preview report, not production rows.
3. Compare:
   - chunk count reduction
   - max child length
   - tiny chunk rate
   - parent title quality
   - evidence judge hit quality on sample questions
4. If sample passes, run controlled re-chunk/re-index by target and source group.

## Remaining Risks

- Some current rows still have old generic titles such as `항내용#1`; future sync is fixed, but existing rows need controlled rechunk to correct them.
- `official_chunks` still contains legacy official_doc points. It is not on the active search path, but cleanup policy is still pending.
- Full law/admrul reindex is large and should use the guarded batch/direct fallback policy.
