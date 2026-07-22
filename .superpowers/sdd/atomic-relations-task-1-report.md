# Atomic Relations Task 1: Evidence Atomizer Report

## Status

DONE

Implemented the bounded Evidence Atomizer exactly as specified in
`atomic-relations-task-1-brief.md`. No runtime or service action was taken, and
no file was staged, committed, reset, reverted, cleaned, or moved to a
worktree.

## TDD Record

### Test-first change

Created `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizerTests.java`
with the five required real-behavior tests before creating any production
class:

1. Broad rule separated from its exception.
2. General prohibition separated from an allowed exception.
3. OCR list markers split while a condition stays with its conclusion.
4. A comma-delimited condition stays attached to its conclusion.
5. Null and blank evidence produce no atoms.

### Preliminary sandbox failure

Command:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests" test
```

Result: exit code `1` before compilation. The sandbox could not access Maven
Central to resolve `org.springframework.boot:spring-boot-starter-parent:4.0.5`
and reported `Permission denied: getsockopt`. This was an environment failure
and was not counted as the TDD RED.

### Valid RED

The exact command was rerun with approved network access:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests" test
```

Result: exit code `1`, with the expected feature-missing compilation failure:

```text
[ERROR] COMPILATION ERROR :
[ERROR] .../ClaimEvidenceAtomizerTests.java:[10,23] cannot find symbol
  symbol:   class ClaimEvidenceAtomizer
  location: class com.kaces.pandora.ai.answer.ClaimEvidenceAtomizerTests
[ERROR] Failed to execute goal ...:testCompile ... Compilation failure
```

This is the correct RED because the test compiled far enough to reference the
required API and failed only because the production class did not yet exist.

### Minimum implementation

Created `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java`
with:

- package-private class and `List<String> atomize(String text)` method;
- structural and connective regular-expression boundaries only;
- OCR/list-marker removal and whitespace normalization;
- `LinkedHashSet` source-order preservation and duplicate removal;
- no Spring annotation, external dependency, or semantic classification.

### GREEN

Command:

```powershell
.\mvnw.cmd -q "-Dtest=ClaimEvidenceAtomizerTests" test
```

Result: exit code `0`. Quiet Maven output was empty. The generated Surefire
report confirmed:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

## Changed Files

- Added `src/main/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizer.java`.
- Added `src/test/java/com/kaces/pandora/ai/answer/ClaimEvidenceAtomizerTests.java`.
- Added this required report:
  `.superpowers/sdd/atomic-relations-task-1-report.md`.

No pre-existing source or test file was modified by this task.

## Self-Review

- The atomizer consumes one nullable raw evidence string and returns a
  package-private `List<String>`.
- Null and blank input return an empty immutable list.
- Empty fragments are discarded after marker removal, whitespace
  normalization, and trimming.
- `LinkedHashSet` makes output deterministic in first-occurrence source order.
- Rule/exception connectors in the brief are structural split points; the
  class assigns no supported, contradicted, permission, obligation, or target
  meaning.
- The comma-only condition fixture remains one atom, confirming that generic
  commas do not detach a condition from its conclusion.
- The implementation is isolated and is not yet wired into
  `ClaimEvidenceMatcher`; that integration is outside Task 1.

## Dirty-Tree Verification

```powershell
git diff --check
```

Result: exit code `0`. Git emitted existing LF-to-CRLF working-copy warnings
for pre-existing tracked files but no whitespace error.

Because the two Task 1 files are untracked, each was also inspected with
`git diff --no-index --check`. Git returned its normal difference exit code
`1` and only the LF-to-CRLF warning; it reported no whitespace error.

```powershell
git status --short --branch
```

Result: exit code `0`; the checkout remains on the pre-existing dirty
`main...origin/main [ahead 3]`. Both Task 1 files remain untracked (`??`), with
no staged entry. Existing unrelated modified and untracked files were
preserved.

## Verification Boundary

Only the focused atomizer test class was run, as required by the bounded Task
1 brief. The full Maven suite, RAG evaluation gate, frontend build, and runtime
checks were not run. Task 1 does not modify integrated RAG behavior.
