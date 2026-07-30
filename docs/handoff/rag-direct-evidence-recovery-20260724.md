# RAG Direct Evidence Recovery Handoff - 2026-07-24

## Repository state

- Shared workspace: `C:\dev\workspace-egov\pandora`
- Shared workspace branch: `main`
- Feature worktree: `C:\dev\workspace-egov\pandora\.worktrees\rag-direct-evidence-recovery`
- Feature branch: `codex/rag-direct-evidence-recovery`
- Base implementation commit already merged to `main`: `1d9fa694`

## Completed

- Korean query normalization removes `개인정보라고` to `개인정보`.
- Weak query terms such as `만으로`, `만으로도` are excluded.
- Indexed RAG lexical search v2, core-noun timeout retry, direct-ground fallback search,
  `concept_relevant` role separation, regression case, and UTF-8 PDF filename response
  handling are included in commit `1d9fa694`.
- Lexical index v2 was backfilled for all 84,248 RAG chunks.
- The privacy email targeted evaluation now retrieves the expected direct evidence:
  `2023년 개인정보 분쟁조정 사례집`, page 46.
- The remaining targeted-evaluation failure was traced to
  `AnswerQuestionAlignmentVerifier`: the claim was `SUPPORTED`, but the natural
  classification question produced `QUESTION_PROFILE_EMPTY`.
- Added a fallback classification frame for natural forms such as:
  - `X를 Y라고 볼 수 있나`
  - `X를 Y로 볼 수 있나`
  - `X는 Y에 해당하나`
  - `X는 Y인가`
- The fallback is used only when configured intent rules did not already produce a
  relation requirement. It keeps the requested subject mandatory and does not loosen
  `ClaimVerifier`.
- Added positive and negative alignment regressions and a real answer-repair regression.

## Verification completed

- Focused RAG tests passed:
  - `KoreanQueryNormalizerTests`
  - `AnswerQuestionAlignmentVerifierTests`
  - `GroundedAnswerRepairServiceTests`
  - `AnswerVerificationServiceTests`
  - `LawAiAnswerServiceEvidenceGateTests`
- Full backend test passed:
  - `Tests run: 852, Failures: 0, Errors: 0, Skipped: 0`
- Frontend production build passed with Vite.

## Pending after resume

1. Review the final feature-branch diff and commit if this handoff is not yet committed.
2. Fast-forward/merge the feature branch into shared `main`.
3. Build the fat jar from `main`.
4. Restart only the 8080 app-dev instance with runtime scripts.
5. Do not restart or stop the 18080 batch-runner.
6. Re-run targeted eval case `privacy-email-personal-info`.
7. Verify the generated answer now passes answer alignment and claim verification.
8. Verify a PDF detail response returns a valid UTF-8 `Content-Disposition` filename.
9. Report remaining risks; full 1,004-case eval is not part of this immediate checkpoint.

## Useful commands

```powershell
node .\scripts\rag-eval-gate.js
```

For the targeted case, set `RAG_EVAL_CASE_IDS=privacy-email-personal-info` in the
process environment before running the gate.
