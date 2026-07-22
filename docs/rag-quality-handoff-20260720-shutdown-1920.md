# Pandora RAG quality shutdown handoff — 2026-07-20 19:20 KST

## Safety state

- All current tracked and untracked workspace changes are saved on disk.
- No files were staged, committed, reset, reverted, or cleaned.
- No Maven/Surefire test process remained after the interrupted command.
- `8080`: listening, PID `4416`; still running the older jar (`51,914,034` bytes, updated `2026-07-20 17:05:29`).
- `6333` Qdrant: listening, PID `26020`.
- `18080`: not listening; stale PID file `runtime/batch-runner/pandora-18080.pid` still says `7504`. It was not touched.
- The newest matcher/atomizer code has **not** been packaged or deployed to `8080`.

## Exact interruption point

The independent final review reported six remaining Important fail-open cases.

Completed after that review:

1. Explicit claim subjects and objects now require corresponding evidence roles instead of treating missing roles as wildcards.
2. Role identity comparison now uses exact canonical identity; generic substring equivalence was removed.
3. Whitespace-delimited coordinated subjects (`기관 및 사업자`) were added and their focused GREEN test passed.
4. Whitespace-delimited coordinated objects (`신청서 및 보고서`) were added after its RED test failed as expected.

The following combined GREEN command was started immediately after the coordinated-object patch, but the turn was intentionally interrupted before any result was returned:

```powershell
.\mvnw.cmd "-Dtest=ClaimEvidenceMatcherRelationTests#whitespaceDelimitedCoordinatedObjectsMustAllAlign+coordinatedObjectsCannotBorrowOnlyTheFinalObject+sameCoordinatedObjectsRemainSupported+whitespaceDelimitedCoordinatedSubjectsMustAllAlign+everyExplicitlyCoordinatedSubjectMustAlign" test
```

No Maven/Surefire process remains. On resume, rerun that command first. Treat the coordinated-object patch as unverified until it passes.

## Remaining independent-review findings

Continue one item at a time with RED -> minimal generalized fix -> GREEN:

1. Open enumeration followed by a copula bypasses the guard:
   - claim: `예비검토 대상은 공공 모바일 앱 개발·AI 사업 등입니다.`
   - evidence: same sentence with `민간`
   - recognize at least `등입니다`, `등이다`, and `등임`.
2. The generic prohibition mask crosses clause boundaries:
   - claim: `보조금을 신청할 수 있습니다.`
   - evidence: `보조금을 신청하는 것을 금지하지만 이의를 신청할 수 있습니다.`
   - keep polarity/action/object matching clause-local and fail closed for mixed propositions.
3. Structural self-assertion bypasses TOC filtering:
   - claim: `정보화사업은 적용 대상입니다.`
   - evidence begins `목차입니다 보안성 검토 개요 ...`
   - `목차입니다` or `개요입니다` must not override high structural-label density.

After these three fixes, request a fresh independent review and require zero Critical/Important findings before packaging.

## Verification evidence already completed

- Focused matcher/atomizer/verifier suite: `234/234` passed at `2026-07-20 19:03:36 KST`.
- Full Maven suite: `588/588` passed at `2026-07-20 19:06:22 KST`.
  - This was before the final-review role/connector fixes, so it must be rerun.
- Relation tests after strict subjects/objects and exact role identity: `140/140` passed at `2026-07-20 19:14:58 KST`.
- Whitespace-delimited coordinated-subject focused test: passed at `2026-07-20 19:17:16 KST`.
- Whitespace-delimited coordinated-object RED test: failed as expected (`SUPPORTED` instead of `INSUFFICIENT`) at `2026-07-20 19:18:06 KST`.
- Its production patch is present, but the GREEN verification was interrupted.

Earlier in this work session, all reviewed fail-open cases for short headings, enumeration particles, coordinated objects, possessive responsibility, nested prohibition requests, restrictive remainders, verb-ending conditions, missing recipients, cross-subject responsibility, spaced universal scope, date boundaries, and repeated-whitespace legal citations were reproduced and repaired with focused tests.

## Current checkpoint hashes

```text
E6F022EBFEE45A0DD28246A2245288A137DC6A4E87DB917FC30374818AFD32A1  src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java
B52B6D03B07325BF9AF5CC815C9FF71CAD60B525FF5703AF2A5653F62C7EBA48  src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java
C4BD5A94F39ACC4F46FAFA16329FA1D8D38D264EBC1C891B353B42DF04EDB6F1  src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcherRelationTests.java
93CA49D6CD9EB3B0114FAD98977E24DB9DA3A973E979A8A04BE4AE80FCE15E3B  src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizerTests.java
```

## Resume sequence

1. Read `AGENTS.md` and this handoff.
2. Run `git status --short --branch`; preserve every existing change.
3. Verify the four hashes above.
4. Run the interrupted coordinated-role GREEN command.
5. TDD the three remaining independent-review findings.
6. Run:
   - the full relation test class;
   - atomizer/artifact/canonical/numeric/relation/verifier focused suite;
   - `node --test .\scripts\rag-eval-provenance.test.js`;
   - `.\mvnw.cmd test`;
   - `git diff --check`.
7. Perform self-review and fresh independent review.
8. Only with zero Critical/Important findings: package the new jar and redeploy **8080 only** with the official script. Do not touch `18080`.
9. Run the exact 38-case atomic-relations evaluation twice from zero with unique output paths. Audit the five genuine unsafe cases manually.
10. Only if the targeted safety gate passes, run the full 1,004-case evaluation from zero and recalculate the quality score.

## Evaluation baseline

- Last complete full evaluation remains `952/1,004` (`94.82%`), score `7.2/10`.
- The latest 38-case run was executed against the older deployed jar, so it is not final evidence for the current source.
- Do not change the aggregate score until a fresh full 1,004-case run finishes on the newly deployed, provenance-matched runtime.
