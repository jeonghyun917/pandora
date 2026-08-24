# Document-first Candidate Expansion Verification

Date: 2026-08-24 (Asia/Seoul)

## Verified candidate identity

- Branch: `codex/document-first-candidate-expansion`
- Production/test code commit: `04dbf342c3f113419b67735358d1f3de0748cfd1`
- Production/test code tree: `70c21dca46a9414d76fd0dc1b9e1c6449dd9d145`
- Starting plan commit: `3c441964`
- Committed-default runtime configuration SHA-256:
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`

The configuration hash above was calculated with the same canonical keys and
ordering as `RuntimeConfigurationIdentity.sha256(...)`, using the committed
`application.properties` defaults. It is not a claim about a live candidate
runtime because this candidate JAR was not deployed during verification.

The fingerprinted inputs are OpenAI embedding/answer model and answer controls;
Qdrant base URL, collection names, and vector size; lexical scoring and bounds;
RRF shadow/authority/weights/bounds; coverage-aware policy; and all five
document-expansion fields. The relevant committed policy is:

```text
rrf.shadowEnabled=true
rrf.authoritative=false
coverage.enabled=false
coverage.maxRescues=0
documentExpansion.enabled=true
documentExpansion.authoritative=false
documentExpansion.maxDocuments=3
documentExpansion.maxChunksPerDocument=8
documentExpansion.maxTotalChunks=24
semantic verification authoritative=false
semantic selection authoritative=false
```

## Verification commands and results

The first unquoted PowerShell form of the focused Maven command was rejected by
the shell because commas were parsed as separators; no Maven test started. The
same exact `-Dtest` value was then passed as one quoted argument.

```powershell
.\mvnw.cmd "-Dtest=DocumentSearchAnchorExtractorTests,QuestionSearchPlanTests,LawAiDocumentExpansionPropertiesTests,RuntimeConfigurationIdentityTests,LawChunkMapperXmlTests,RagDocumentMapperXmlTests,DocumentCandidateExpansionTests,DocumentExpansionSearchServiceTests,LawAiAnswerServiceDocumentExpansionTests,LawAiAnswerServiceCoverageAwareTests,LawAiRuntimeInfoTests" test
```

- Tests: `107`
- Failures: `0`
- Errors: `0`
- Skipped: `0`

```powershell
node --test .\scripts\rag-retrieval-eval.test.js .\scripts\document-expansion-selection.test.js .\scripts\coverage-aware-selection.test.js .\scripts\rrf-weight-selection.test.js .\scripts\rag-eval-provenance.test.js
```

- Tests: `111`
- Passed: `111`
- Failed/cancelled/skipped/todo: `0/0/0/0`

```powershell
.\mvnw.cmd test
```

- Tests: `1301`
- Failures: `0`
- Errors: `0`
- Skipped: `18`
- The 18 skips are the opt-in
  `LawMissingEmbeddingRepairOperationMariaDbIntegrationTests`, guarded by
  `pandora.mariadb.it=true`; they are unrelated to document expansion.

`git diff --check` reported no whitespace errors before documentation edits.
The worktree was clean on the expected branch at the verified code commit.

## Scoped implementation self-review

The Task 8 implementer performed one scoped review of the following invariants
and found no Critical or Important defect requiring a fix round. The parent
workflow still performs its separate task review after this documentation
commit.

- Production anchor extraction, document selection, and expansion search do not
  consume evaluation case IDs, oracle groups, expected evidence, or hard-coded
  document IDs.
- Both law and RAG mapper statements use active/current searchable rows, bound
  document queries with `LIMIT`, rank chunks per document, and apply a global
  `LIMIT`. The pure selector and answer-service trust boundary independently
  enforce `3/8/24`.
- While document expansion is non-authoritative, existing vector, lexical,
  pure-RRF, coverage-aware, and `searchedChunks` control orders remain
  unchanged. Focused integration tests also assert no additional OpenAI
  embedding/answer or Qdrant request.
- New debug/capture fields expose bounded rank, identity, anchor/reason,
  overlap, and audit-group indexes; the offline capture does not persist
  candidate text or secrets.
- Invalid bounds, ambiguity, malformed identity, database error, timeout, and
  provenance mismatch fail closed to the baseline.
- The offline selector requires the frozen `7/14/23` control baseline, repeated
  improvement in both runs, no baseline-passing case loss, stable policy, zero
  request/Qdrant errors, and identical immutable provenance.

## Runtime and database safety evidence

`scripts/status-pandora.ps1` was run read-only. It reported the app-dev Windows
service stopped/disabled, a stale 8080 PID file, no installed batch-runner
service, a stale 18080 PID file, and no local `LISTENING` entry for 8080, 18080,
or 6333. No service was started, stopped, restarted, or promoted. Port 18080 and
`output/` were not touched. No OpenAI or Qdrant evaluation/mutation ran.

The local MariaDB service was running, but no candidate app-dev runtime or
document-expansion mapper integration harness was available. Starting a Spring
context solely for this check could execute configured schema-maintenance hooks,
so the deferred Task 3 live MyBatis/MariaDB invocation was not performed. The
mapper XML was parsed, rendered, parameter-bound, and covered by the focused
tests (`LawChunkMapperXmlTests` and `RagDocumentMapperXmlTests`). A live mapper
execution remains a pre-evaluation fence for Task 9 and must use the documented
candidate runtime without ad-hoc service changes.

## Promotion state

No external evaluation has run for this change. Document expansion remains
shadow-only and all existing authority flags remain off. Task 9 must first build
the candidate artifact, prepare a fresh immutable manifest, obtain exact
payload approval, and recheck runtime/index/config/Qdrant/MariaDB fences before
one execution.
