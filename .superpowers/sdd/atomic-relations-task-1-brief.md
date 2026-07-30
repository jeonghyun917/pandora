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
