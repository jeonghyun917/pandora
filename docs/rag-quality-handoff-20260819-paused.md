# RAG Tasks 7-15 pause handoff (2026-08-19)

## Safe pause state

- Feature branch/worktree: `codex/rag-direct-evidence-recovery`
- Last implementation commit: `59881a61` (`fix: stabilize equal-score vector ranks`)
- Prior BM25 latency/evaluation commit: `3b4a8d26` (`fix: keep BM25 shadow within latency budget`)
- Verified package SHA-256: `FC346271E86D7AC824FD07BE93888191CF53435D388A068502F0BD6C2EEBC549`
- Java verification: 1,184 tests, 0 failures, 0 errors, 18 skipped.
- Node verification before the final Java-only tie-break change: 133 tests, 0 failures.
- The 8080 app-dev runtime was stopped with `scripts/stop-pandora.ps1 -Role app-dev` at the user's request.
- Port 18080 and `output/` were never touched.

## Stable corpus/runtime evidence before shutdown

- Law DB/Qdrant: `211548 / 211548`
- RAG DB/Qdrant: `84248 / 84248`
- Qdrant ready: `true`; search failure count: `0`
- Lexical revision: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`
- Last stable manifest: `logs/task15-shadow-manifest-bm25-stable-vector-20260819.json`

## Retrieval shadow result

RRF remains shadow-only (`rrf-authoritative=false`). Do not promote it from the
current evidence:

- Difficult-12 BM25 ranks were repeatable, but fused ranks still differed for
  3 cases after deterministic equal-score vector tie-breaking.
- Explicit-oracle presence at top 30 was 3/12 for BM25 and 5/12 for fused,
  below the required 80% threshold.
- The latest warm BM25 nearest-rank p95 was 615 ms, above the 500 ms gate.
- False-ground cases were 0.

Artifacts:

- `logs/task15-difficult12-stable-vector-run1.json`
- `logs/task15-difficult12-stable-vector-run2.json`

## Exact resume point

The semantic-shadow difficult-12 release gate was started, then deliberately
interrupted before completion because the user needed to shut down the PC. No
result artifact from that interrupted run should be treated as evidence.

On resume:

1. Restore Qdrant 6333 and app-dev 8080 using the official scripts; do not start
   or touch 18080.
2. Verify the deployed JAR is commit `59881a61`'s package (or rebuild it), then
   capture a new stable manifest because the runtime instance will change.
3. Re-run the semantic-shadow difficult-12 gate from the beginning with a new
   output path.
4. Keep `semantic-authoritative=false` unless unsafe disagreements are zero and
   all required gates pass.
5. Keep `rrf-authoritative=false`; its measured acceptance gate is currently a
   fail, not an unverified pass.

Unrelated pre-existing untracked files under `docs/superpowers/plans/` and
`output/` were preserved and must not be added or removed as part of this work.
