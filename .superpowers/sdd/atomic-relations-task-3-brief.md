### Task 3: Artifact-Derived End-to-End Regression Controls

**Files:**
- Create: `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcherArtifactRegressionTests.java`
- Modify only if a RED fixture proves a remaining generalized defect:
  `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java`
  or `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java`

**Interfaces:**
- Consumes: unchanged `ClaimVerifier.verifyDetailed(String, List<LawAiAnswerGround>)`.
- Produces: deterministic controls derived from the canonical 2026-07-20 result; production behavior remains case-agnostic.

- [ ] **Step 1: Add representative false-positive and genuine-failure fixtures**

Create tests covering:

```java
@Test
void directSoftwareRuleSurvivesHardwareExceptionInTheSameGround() {
	String answer = "국가기관 등이 발주하는 모든 소프트웨어사업은 과업심의 적용 대상입니다.";
	ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
		"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이며, "
			+ "단순 H/W 도입·설치는 소프트웨어사업으로 볼 수 없어 비대상입니다."
	)));
	assertThat(result.verifiedAnswer()).isEqualTo(answer);
	assertThat(result.contradictedClaims()).isEmpty();
}

@Test
void explicitCctvExceptionSurvivesTheGeneralProhibition() {
	String answer = "범죄의 예방 및 수사를 위하여 필요한 경우 CCTV를 설치할 수 있습니다.";
	ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
		"공개된 장소 설치는 원칙적으로 금지됩니다. "
			+ "예외적으로 범죄의 예방 및 수사를 위하여 필요한 경우 설치할 수 있습니다."
	)));
	assertThat(result.verifiedAnswer()).isEqualTo(answer);
	assertThat(result.contradictedClaims()).isEmpty();
}

@Test
void unrelatedSkipListDoesNotConflictWithChecklistDuty() {
	String answer = "클라우드 이용 사업은 시스템 중요도 분류 체크리스트를 제출해야 합니다.";
	ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
		ground("클라우드 이용 사업은 시스템 중요도 분류 체크리스트를 포함하여 제출해야 합니다."),
		ground("일부 단순 용역은 보안성 검토 절차 이행 생략 대상입니다.")
	));
	assertThat(result.verifiedAnswer()).isEqualTo(answer);
	assertThat(result.contradictedClaims()).isEmpty();
}

@Test
void unrelatedPrivacyProhibitionIsInsufficientNotContradictory() {
	String answer = "개인정보처리자는 개인정보 처리방침을 공개해야 합니다.";
	ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
		ground("개인정보취급자는 업무 목적 외 불필요한 접근을 금지합니다.")
	));
	assertThat(result.insufficientEvidence()).isTrue();
	assertThat(result.contradictedClaims()).isEmpty();
}

@Test
void exactOppositeClaimStillFailsClosed() {
	String answer = "보안성검토 절차를 생략할 수 없습니다.";
	ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
		ground("상기 항목에 해당하는 정보화사업은 보안성검토 절차를 생략할 수 있습니다.")
	));
	assertThat(result.insufficientEvidence()).isTrue();
	assertThat(result.contradictedClaims()).containsExactly(answer);
}

@Test
void mergedWhistleblowerRoutesRemainFailClosed() {
	String answer = "이미 불이익을 받은 공익신고자는 불이익조치 금지 신청을 할 수 있습니다.";
	ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
		ground("불이익조치를 받을 우려가 명백한 경우 위원회에 불이익조치 금지를 신청할 수 있습니다."),
		ground("공익신고를 이유로 불이익조치를 받은 때에는 위원회에 보호조치를 신청할 수 있습니다.")
	));
	assertThat(result.insufficientEvidence()).isTrue();
	assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
}
```

Use a local `ground(String snippet)` helper matching the existing
`LawAiAnswerGround` constructor. Add the separation-duty/exception and
caution-only fixtures from the design so this class contains eight artifact
regressions in total.

- [ ] **Step 2: Run the new class and record RED if any generalized gap remains**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceMatcherArtifactRegressionTests" test
```

Expected: if Task 2 fully covers the class, the new tests are GREEN immediately
and are characterization/integration controls. If a test is RED, confirm the
failure is one of the approved generalized boundaries before changing production
code; do not add a fixture-specific branch.

- [ ] **Step 3: Apply only a proven generalized correction**

Allowed corrections are limited to:

- an omitted structural boundary in `ClaimEvidenceAtomizer`;
- proposition alignment ordering in `ClaimEvidenceMatcher`;
- role normalization that incorrectly treats the same actor/object as different.

The correction must be represented by the failing fixture and must not change
`ClaimVerifier` fail-closed policy.

- [ ] **Step 4: Run focused GREEN and the full backend suite**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests,ClaimEvidenceMatcherRelationTests,ClaimEvidenceMatcherNumericTests,ClaimVerifierTests,ClaimEvidenceMatcherArtifactRegressionTests" test
.\mvnw.cmd test
```

Expected: focused tests and the complete Maven suite finish with 0 failures and
0 errors.

- [ ] **Step 5: Independent review gate**

Provide the task brief, implementer report, and a before/after file snapshot
diff to a read-only reviewer. The reviewer must independently verify:

- spec compliance;
- no case-specific production logic;
- true contradictions and compound overreach remain fail-closed;
- coverage and role gates cannot promote unsupported claims;
- tests exercise real matcher/verifier behavior.

Resolve all Critical and Important findings and rerun the covering focused
tests before re-review.

---
