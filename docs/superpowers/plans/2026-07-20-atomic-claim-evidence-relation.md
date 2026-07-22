# Pandora Atomic Claim–Evidence Relation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the 33 observed false `CONTRADICTED`/`CONFLICTED` outcomes without weakening fail-closed handling for the five genuine contradiction or overreach cases.

**Architecture:** A package-private pure `ClaimEvidenceAtomizer` splits OCR-heavy evidence into proposition-sized fragments while preserving condition/conclusion scope. `ClaimEvidenceMatcher` then applies coverage, relation/condition/numeric/action/category, and explicit role-direction alignment before comparing polarity; conflict is possible only between qualifying evidence atoms for the same proposition.

**Tech Stack:** Java 17+, Spring Boot, JUnit 5, AssertJ, Maven Wrapper, Node.js RAG evaluation gate, PowerShell runtime scripts.

## Global Constraints

- Correct the 33 observed false contradiction/conflict cases to `SUPPORTED` or `INSUFFICIENT`.
- Keep the one genuine direct contradiction and four genuine scope/source/procedure overreach cases fail-closed.
- Do not add case-ID, exact-question, or document-title branches to production code.
- Do not add an external NLP, LLM, or NLI dependency.
- Do not change retrieval, EvidenceJudge, generation prompts, cache behavior, or streaming behavior in this slice.
- Do not weaken `ClaimVerifier` whole-answer fail-closed behavior.
- Preserve every pre-existing dirty and untracked change. Do not reset, revert, clean, stage, or commit.
- Continue in the current dirty `main` checkout; do not create a worktree that would omit the uncommitted matcher work.
- Runtime mutation is limited to app-dev 8080 through repository scripts. Never stop, restart, promote, or otherwise mutate batch-runner 18080.
- Use TDD for every production behavior change: record RED, implement the minimum generalized change, record GREEN, self-review, then request an independent task review.

---

### Task 1: Evidence Atomizer

**Files:**
- Create: `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java`
- Create: `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizerTests.java`

**Interfaces:**
- Consumes: one nullable raw evidence string.
- Produces: package-private `List<String> atomize(String text)` with stable source order, no blank atoms, and no semantic classification.

- [ ] **Step 1: Write the failing atomizer tests**

Create `ClaimEvidenceAtomizerTests` with these real behavior tests:

```java
package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimEvidenceAtomizerTests {

	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();

	@Test
	void separatesBroadRuleFromItsException() {
		assertThat(atomizer.atomize(
			"모든 소프트웨어사업은 과업심의 대상이며, 단순 H/W 도입은 비대상입니다."
		)).containsExactly(
			"모든 소프트웨어사업은 과업심의 대상이며",
			"단순 H/W 도입은 비대상입니다."
		);
	}

	@Test
	void separatesGeneralProhibitionFromAllowedException() {
		assertThat(atomizer.atomize(
			"공개된 장소 설치는 원칙적으로 금지됩니다. "
				+ "예외적으로 범죄 예방을 위해 설치할 수 있습니다."
		)).containsExactly(
			"공개된 장소 설치는 원칙적으로 금지됩니다.",
			"예외적으로 범죄 예방을 위해 설치할 수 있습니다."
		);
	}

	@Test
	void splitsOcrListMarkersButKeepsConditionWithItsConclusion() {
		assertThat(atomizer.atomize(
			"검토 항목 • 접근권한을 분리해야 합니다. ※ 분리가 불필요한 경우 파기할 수 있습니다."
		)).containsExactly(
			"검토 항목",
			"접근권한을 분리해야 합니다.",
			"분리가 불필요한 경우 파기할 수 있습니다."
		);
	}

	@Test
	void keepsCommaDelimitedConditionAndConclusionTogether() {
		assertThat(atomizer.atomize(
			"법령에서 정한 경우, 정보화사업은 검토 대상입니다."
		)).containsExactly("법령에서 정한 경우, 정보화사업은 검토 대상입니다.");
	}

	@Test
	void returnsNoAtomsForMissingText() {
		assertThat(atomizer.atomize(null)).isEmpty();
		assertThat(atomizer.atomize("  \r\n ")).isEmpty();
	}
}
```

- [ ] **Step 2: Run the tests and record RED**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests" test
```

Expected RED: compilation fails because `ClaimEvidenceAtomizer` does not exist.

- [ ] **Step 3: Implement the pure atomizer**

Create the class with no Spring annotation and no external dependency:

```java
package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class ClaimEvidenceAtomizer {

	private static final Pattern STRUCTURAL_BOUNDARY = Pattern.compile(
		"(?<=[!?])\\s+"
			+ "|(?<=[가-힣A-Za-z][.])\\s+"
			+ "|[;；]"
			+ "|\\R+"
			+ "|(?=[①-⑳•‣□○※])"
			+ "|\\s+(?=\\d{1,2}[.)]\\s)"
			+ "|\\s+(?=p\\.\\s*\\d+\\b)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern CONNECTIVE_BOUNDARY = Pattern.compile(
		"(?<=이며)[,，]?\\s+"
			+ "|(?<=이고)[,，]?\\s+"
			+ "|(?<=되며)[,，]?\\s+"
			+ "|(?<=하되)[,，]?\\s+"
			+ "|(?=다만[,，]?\\s+)"
			+ "|(?=예외적으로\\s+)"
	);
	private static final Pattern LEADING_LIST_MARKER = Pattern.compile(
		"^[①-⑳•‣□○※]+\\s*"
	);

	List<String> atomize(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		Set<String> atoms = new LinkedHashSet<>();
		for (String structural : STRUCTURAL_BOUNDARY.split(text.replace('\r', '\n'))) {
			for (String clause : CONNECTIVE_BOUNDARY.split(structural)) {
				String cleaned = LEADING_LIST_MARKER.matcher(clause)
					.replaceFirst("")
					.replaceAll("\\s+", " ")
					.trim();
				if (!cleaned.isBlank()) {
					atoms.add(cleaned);
				}
			}
		}
		return List.copyOf(new ArrayList<>(atoms));
	}
}
```

- [ ] **Step 4: Run the atomizer tests and record GREEN**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests" test
```

Expected GREEN: 5 tests, 0 failures, 0 errors.

- [ ] **Step 5: Self-review and preserve the dirty tree**

Run:

```powershell
git diff --check
git status --short --branch
```

Confirm the new class only performs structural splitting, conditions remain attached to conclusions, and no file is staged or committed.

---

### Task 2: Proposition Alignment Before Polarity

**Files:**
- Modify: `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java`
- Modify: `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcherRelationTests.java`

**Interfaces:**
- Consumes: Task 1 `ClaimEvidenceAtomizer.atomize(String)`.
- Produces: existing `ClaimEvidenceMatcher.Match` API with unchanged enum values and record fields.

- [ ] **Step 1: Add failing relation fixtures**

Add these tests to `ClaimEvidenceMatcherRelationTests`:

```java
@Test
void supportsBroadRuleWithoutConflictingWithItsScopedException() {
	ClaimEvidenceMatcher.Match match = matcher.match(
		"국가기관 발주 소프트웨어사업은 과업심의 대상입니다.",
		List.of(ground(
			"국가기관 발주 소프트웨어사업은 과업심의 대상이며, "
				+ "단순 H/W 도입은 과업심의 비대상입니다."
		))
	);

	assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
}

@Test
void supportsTheScopedExceptionFromTheSameEvidenceFragment() {
	ClaimEvidenceMatcher.Match match = matcher.match(
		"단순 H/W 도입은 과업심의 비대상입니다.",
		List.of(ground(
			"국가기관 발주 소프트웨어사업은 과업심의 대상이며, "
				+ "단순 H/W 도입은 과업심의 비대상입니다."
		))
	);

	assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
}

@Test
void supportsAllowedExceptionWithoutConflictingWithGeneralProhibition() {
	ClaimEvidenceMatcher.Match match = matcher.match(
		"범죄 예방을 위해 CCTV를 설치할 수 있습니다.",
		List.of(ground(
			"공개된 장소의 CCTV 설치는 원칙적으로 금지됩니다. "
				+ "예외적으로 범죄 예방을 위해 CCTV를 설치할 수 있습니다."
		))
	);

	assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
}

@Test
void unrelatedOppositeActionIsInsufficientRatherThanContradictory() {
	ClaimEvidenceMatcher.Match match = matcher.match(
		"기관은 신청서를 제출할 수 있습니다.",
		List.of(ground("기관은 신청서를 열람할 수 없습니다."))
	);

	assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
}

@Test
void reversedActorAndRecipientAreNotTheSameProposition() {
	ClaimEvidenceMatcher.Match match = matcher.match(
		"기관은 사업자에게 결과를 통지해야 합니다.",
		List.of(ground("사업자는 기관에게 결과를 통지해야 합니다."))
	);

	assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
}

@Test
void preservesTrueConflictForTheSameAtomicProposition() {
	ClaimEvidenceMatcher.Match match = matcher.match(
		"단순 H/W 도입은 과업심의 대상입니다.",
		List.of(
			ground("단순 H/W 도입은 과업심의 대상입니다."),
			ground("단순 H/W 도입은 과업심의 비대상입니다.")
		)
	);

	assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
}
```

- [ ] **Step 2: Run the relation suite and record RED**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceMatcherRelationTests" test
```

Expected RED: the mixed rule/exception, unrelated opposite action, or reversed-role fixtures fail under fragment-global polarity and pre-alignment contradiction handling. The true-conflict control must already pass or remain passing.

- [ ] **Step 3: Use the atomizer in the evidence index**

Replace the matcher-local sentence boundary with:

```java
private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();
```

In `addEvidenceFragments`, replace direct regex splitting with:

```java
for (String fragment : atomizer.atomize(text)) {
	String cleaned = fragment.replaceAll("\\s+", " ").trim();
	if (cleaned.length() < 4) {
		continue;
	}
	// Preserve the existing EvidenceSentence construction and de-duplication.
}
```

Delete only the now-unused matcher `SENTENCE_BOUNDARY` constant.

- [ ] **Step 4: Apply one coverage gate to support and contradiction candidates**

In `match`, compute and enforce required coverage before `relation`:

```java
double coverage = (double) overlap / Math.max(1, new LinkedHashSet<>(claimTokens).size());
double requiredCoverage = claimNumbers.isEmpty() ? MIN_COVERAGE : 0.20d;
if (coverage < requiredCoverage) {
	continue;
}
ClaimSemantics evidenceSemantics = ClaimSemantics.from(sentence.text());
Relation relation = relation(claimSemantics, evidenceSemantics, sentence.anchorContext());
```

Keep the existing numeric prefilter and scoring. Remove the duplicate
`coverage >= requiredCoverage` check from the supported-candidate branch.

- [ ] **Step 5: Add explicit role-direction signatures**

Add three role patterns:

```java
private static final Pattern SUBJECT_ROLE = Pattern.compile(
	"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:은|는|이|가)(?=\\s|$)",
	Pattern.CASE_INSENSITIVE
);
private static final Pattern OBJECT_ROLE = Pattern.compile(
	"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:을|를)(?=\\s|$)",
	Pattern.CASE_INSENSITIVE
);
private static final Pattern RECIPIENT_ROLE = Pattern.compile(
	"(?:^|\\s)([\\p{IsHangul}a-z0-9]{2,}?)(?:에게|한테)(?=\\s|$)",
	Pattern.CASE_INSENSITIVE
);
```

Extend `ClaimSemantics` with `PropositionRoles roles`. Build it from the raw
text:

```java
private record PropositionRoles(
	Set<String> subjects,
	Set<String> objects,
	Set<String> recipients
) {
	static PropositionRoles from(String text) {
		return new PropositionRoles(
			roleTokens(SUBJECT_ROLE, text),
			roleTokens(OBJECT_ROLE, text),
			roleTokens(RECIPIENT_ROLE, text)
		);
	}

	private static Set<String> roleTokens(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(String.valueOf(text == null ? "" : text));
		Set<String> tokens = new LinkedHashSet<>();
		while (matcher.find()) {
			String normalized = KoreanQueryNormalizer.normalizeForMatch(matcher.group(1));
			if (normalized.length() >= 2) {
				tokens.add(normalized);
			}
		}
		return Set.copyOf(tokens);
	}
}
```

Add outer matcher helpers:

```java
private boolean rolesAligned(PropositionRoles claim, PropositionRoles evidence) {
	return roleSetAligned(claim.subjects(), evidence.subjects())
		&& roleSetAligned(claim.objects(), evidence.objects())
		&& roleSetAligned(claim.recipients(), evidence.recipients());
}

private boolean roleSetAligned(Set<String> claim, Set<String> evidence) {
	if (claim.isEmpty() || evidence.isEmpty()) {
		return true;
	}
	return claim.stream().anyMatch(left ->
		evidence.stream().anyMatch(right ->
			left.equals(right)
				|| (left.length() >= 3 && right.length() >= 3
					&& (left.contains(right) || right.contains(left)))
		)
	);
}
```

- [ ] **Step 6: Reorder proposition gates before polarity**

In `relation`, keep relation, condition, and numeric anchor checks first.
After the no-explicit-semantics check, evaluate in this order:

```java
if (!rolesAligned(claim.roles(), evidence.roles())) {
	return Relation.NOT_ENTAILED;
}
if (!claim.permissionActions().isEmpty()
	&& !evidence.permissionActions().containsAll(claim.permissionActions())) {
	return Relation.NOT_ENTAILED;
}
if (!evidence.categories().containsAll(claim.categories())) {
	return Relation.NOT_ENTAILED;
}
if (claim.narrowingCondition() != evidence.narrowingCondition()) {
	return Relation.NOT_ENTAILED;
}
if (evidence.conditional() && !claim.conditional()) {
	return Relation.NOT_ENTAILED;
}
if (claim.conditional() && !evidence.conditional()
	&& claim.requiredConditionAnchors().isEmpty()) {
	return Relation.NOT_ENTAILED;
}
if (opposite(claim.targetMode(), evidence.targetMode())
	|| opposite(claim.obligationMode(), evidence.obligationMode())
	|| opposite(claim.permissionMode(), evidence.permissionMode())) {
	return Relation.CONTRADICTED;
}
if (!sameOrUnspecified(claim.targetMode(), evidence.targetMode())
	|| !sameOrUnspecified(claim.obligationMode(), evidence.obligationMode())
	|| !sameOrUnspecified(claim.permissionMode(), evidence.permissionMode())) {
	return Relation.NOT_ENTAILED;
}
return Relation.COMPATIBLE;
```

Do not change the final `SUPPORTED`/`CONTRADICTED`/`CONFLICTED`/`INSUFFICIENT`
public contract.

- [ ] **Step 7: Run focused GREEN tests**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests,ClaimEvidenceMatcherRelationTests,ClaimEvidenceMatcherNumericTests,ClaimVerifierTests" test
```

Expected GREEN: all atomizer, relation, numeric, and verifier tests pass with 0 failures and 0 errors.

- [ ] **Step 8: Self-review**

Inspect:

```powershell
git diff --check
git diff -- src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceMatcher.java
git status --short --branch
```

Confirm role alignment is conservative, missing roles do not fabricate support,
conditions are checked before polarity, true-conflict controls remain green,
and no unrelated production file changed.

---

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

### Task 4: Runtime Promotion and Repeated Evaluation

**Files:**
- Modify: `.superpowers/sdd/progress.md`
- Create: `docs/rag-quality-handoff-20260720-atomic-relations.md`
- Produce logs under `logs/` with unique `atomic-relations` names.

**Interfaces:**
- Consumes: verified backend source and existing Qdrant index.
- Produces: stable-runtime targeted runs, a full 1,004-case run after targeted safety passes, updated score and handoff.

- [ ] **Step 1: Verify runtime state without mutation**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-pandora.ps1
```

Record 8080, 18080, and 6333 state. Do not alter 18080 even if its PID file is stale.

- [ ] **Step 2: Build and validate the staged app-dev JAR**

Run:

```powershell
.\mvnw.cmd -Papp-dev-staged-package -DskipTests package
jar tf .\target-stage\pandora-0.0.1-SNAPSHOT.jar
Get-FileHash -Algorithm SHA256 .\target-stage\pandora-0.0.1-SNAPSHOT.jar
```

Confirm the manifest, `BOOT-INF/classes`, and `BOOT-INF/lib` exist before promotion.

- [ ] **Step 3: Restart only the 8080 app service**

Run the installed app-dev service workflow:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Stop -Role app-dev -Port 8080
Copy-Item -LiteralPath .\target-stage\pandora-0.0.1-SNAPSHOT.jar -Destination .\target\pandora-0.0.1-SNAPSHOT.jar -Force
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Start -Role app-dev -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-pandora.ps1
```

If the service is no longer installed, use only the documented
`start-pandora.ps1 -Role app-dev -Port 8080 -UseJar` fallback. Never invoke a
batch-runner command.

- [ ] **Step 4: Verify runtime provenance**

Call the 8080 runtime-info endpoint and record:

- instance ID;
- artifact SHA/size;
- configuration SHA;
- Qdrant readiness and search-failure count;
- index revision.

Check the same values before and after each adopted evaluation.

- [ ] **Step 5: Run the 38-case gate twice from zero**

Set:

```powershell
$env:RAG_EVAL_CASE_IDS='project-review-simple-software,project-review-pre-consultation-relation,pre-consultation-target,pre-consultation-when,security-review-target,security-review-exception,it-compliance-penalty,egov-preliminary-review-target,rfp-tech-score-table,public-data-db-standard,procurement-catalog-contract,commercial-sw-direct-purchase,whistleblower-protection-scope,video-cctv-guide,personal-info-purpose,privacy-consent-notice-items,pipc-cctv-public-place-exception,pipc-pseudonym-additional-info,project-review-all-sw-projects,project-review-exclusion-hardware,procurement-digital-service-mall,cctv-public-place-rule,cctv-retention-not-fixed-30,whistleblower-disadvantage,security-review-major-infra,security-review-skip-condition,rfp-requirement-method,commercial-sw-direct-buy-target,procurement-catalog-vs-contract,pseudonym-extra-info-separate,whistleblower-protection-action,privacy-retention-notice,privacy-minimum-collection,privacy-destruction-principle,cctv-install-purpose-limit,public-data-meta-management,admrul-notice-exception,public-data-obligation-system'
$env:RAG_EVAL_RESUME='false'
$env:RAG_EVAL_OUTPUT='logs/rag-eval-gate-targeted-atomic-relations-run1-20260720.json'
$env:RAG_EVAL_REPORT='logs/rag-eval-gate-targeted-atomic-relations-run1-20260720.md'
$env:RAG_EVAL_CHECKPOINT='logs/rag-eval-gate-targeted-atomic-relations-run1-20260720-checkpoint.json'
node .\scripts\rag-eval-gate.js
```

Repeat with `run2` output/report/checkpoint names. The evaluator may exit 1 when
some approved genuine failures remain; that is a quality-gate result, not an
execution failure.

- [ ] **Step 6: Adjudicate repeated targeted results**

For both runs, report:

- passed/failed;
- answer-verification pass count;
- number of `CONTRADICTED` and `CONFLICTED` links;
- which of the 33 observed false-positive IDs remain falsely contradicted;
- whether the five genuine cases remain fail-closed;
- run-to-run flips.

Do not claim the relation fix is stable if the two runs materially disagree.

- [ ] **Step 7: Run the final full gate only after targeted safety passes**

Clear `RAG_EVAL_CASE_IDS`, use unique full-run output/report/checkpoint paths,
confirm runtime provenance, and run:

```powershell
node .\scripts\rag-eval-gate.js
```

Verify exactly 1,004 expected, actual, and unique IDs with no missing,
duplicate, unexpected, or order-mismatched results.

- [ ] **Step 8: Final verification and handoff**

Run fresh:

```powershell
.\mvnw.cmd test
node --test .\scripts\rag-eval-provenance.test.js .\scripts\rag-retrieval-eval.test.js
git diff --check
git status --short --branch
```

Write the handoff with exact artifact paths, hashes, runtime identity, test
counts, targeted/full metrics, remaining failures, and an updated honest
10-point score. Preserve all changes without staging or committing.
