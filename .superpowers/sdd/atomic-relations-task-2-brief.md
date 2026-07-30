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
