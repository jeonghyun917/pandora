# Task 1 Report: Expose complete child text only for measurement requests

## Scope delivered

- Added `includeMatchedChildText` to `LawAiDebugRequest`; `includeMatchedChildTextEnabled()` is true only for an explicit Boolean `true` value.
- Added nullable `matchedChildText` to `LawAiDebugResponse.Item`. `@JsonInclude(NON_NULL)` omits the field for normal debug payloads.
- Propagated the explicit flag through `LawAiAnswerService.debug`, `toDebugResponse`, and `toDebugItems`.
- Populated the field from the untruncated `LawSemanticChunkRow.chunkText()` only when the flag is enabled.
- Kept evaluation-case debug items explicitly false. The normal frontend `debugLawAiSearch` request sends no new flag, so it continues to receive no complete matched-child body.
- Did not change answer generation, retrieval ranking, evidence judging/verifying, fail-closed behavior, frontend behavior, or runtime processes.

## TDD evidence

### RED

Command (PowerShell quotes are required so the comma stays within Maven's `-Dtest` argument):

```powershell
.\mvnw.cmd "-Dtest=LawAiDebugResponseItemTests,LawAiAnswerServiceEvidenceGateTests" test
```

Result: expected `BUILD FAILURE` during `testCompile` before production changes. The new tests failed because `LawAiDebugRequest` had only five constructor components and no `includeMatchedChildTextEnabled()` method, while `LawAiDebugResponse.Item` had no `matchedChildText()` accessor. This demonstrates the requested contract was absent.

### GREEN

Command:

```powershell
.\mvnw.cmd "-Dtest=LawAiDebugResponseItemTests,LawAiAnswerServiceEvidenceGateTests" test
```

Result: `BUILD SUCCESS`; 56 tests run, 0 failures, 0 errors, 0 skipped (`LawAiDebugResponseItemTests`: 2; `LawAiAnswerServiceEvidenceGateTests`: 54).

### Full verification

Command:

```powershell
.\mvnw.cmd test
```

Result: `BUILD SUCCESS`; 830 tests run, 0 failures, 0 errors, 0 skipped.

## Files changed

- `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugRequest.java`
- `src/main/java/com/kaces/pandora/ai/answer/LawAiDebugResponse.java`
- `src/main/java/com/kaces/pandora/ai/answer/LawAiAnswerService.java`
- `src/test/java/com/kaces/pandora/ai/answer/LawAiDebugResponseItemTests.java`
- `src/test/java/com/kaces/pandora/ai/answer/LawAiAnswerServiceEvidenceGateTests.java`

## Review notes and concerns

- The default is fail-closed for payload size and exposure: `null`, an absent request object, or an omitted JSON property all leave the matched-child body out of the response; Jackson omits the null response property.
- The only full-text source is `LawSemanticChunkRow.chunkText()` behind the debug request flag. It is not introduced into answer, ranking, verifier, or normal UI paths.
- Full Maven tests issued their existing test warnings (deprecated API, intentional Qdrant failure/retry scenarios, and a dynamic-index warning), but completed successfully.
- No runtime process, including port 18080, was started, stopped, or restarted.

## Commit

Commit message: `feat: expose matched child text for RAG measurement`
