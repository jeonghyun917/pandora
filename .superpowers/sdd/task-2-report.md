# Task 2 Report: Explicit Oracle Coverage Matcher

## Scope

- Added `scripts/lib/rag-explicit-oracle-matcher.js`, an evaluation-only
  matcher that returns the first matching OR alias for one oracle group in one
  input text.
- Added `scripts/rag-evidence-coverage.test.js`.
- No Java or production answer-path files were changed, and this module has no
  imports outside its dedicated Node test.

## RED evidence

1. Ran `node --test scripts/rag-evidence-coverage.test.js` before the matcher
   existed.
   - Result: exit 1; 0 passed, 5 failed.
   - The failures were the intended missing `matchOracleGroup` contract.
2. While comparing Java representative behavior, added the dotted-date fixture
   from `AnswerOracleMatcherTests` and reran the focused Node suite.
   - Result: exit 1; 5 passed, 1 failed.
   - The expected dotted date was split into separate clauses, returning `null`.

## GREEN evidence

1. `node --test scripts/rag-evidence-coverage.test.js`
   - Result: exit 0; 6 passed, 0 failed.
   - Covers OR aliases, material-token coverage in one text, no synthesis
     across items/sentences, positive/negative polarity separation, Korean
     punctuation/spacing normalization, and dotted-date preservation.
2. `./mvnw.cmd -Dtest=AnswerOracleMatcherTests test`
   - Result: exit 0; 20 tests run, 0 failures, 0 errors.
   - Used to compare the Node matcher against representative Java oracle
     semantics, including local polarity and dotted-date clause handling.
3. `git diff --check`
   - Result: no whitespace errors.

## Self-review

- Matching is group-local: one alias satisfies an OR group; later coverage code
  remains responsible for AND aggregation.
- The matcher evaluates each sentence independently and accepts a match only
  when all material tokens occur in that same sentence. It never concatenates
  separate retrieval items or sentences.
- Normalization is general NFKC/lowercase/punctuation-and-space removal;
  particles and local negation markers are general rules, with no case IDs or
  oracle-specific phrase handling.
- The module is unreferenced by Java and existing production JavaScript, so it
  cannot affect answer behavior or runtime processes.

## Commit

- `test: add explicit oracle coverage matcher`

## Concerns

- This is intentionally a measurement approximation of the Java matcher, not a
  replacement or runtime dependency. The focused Java fixture suite passed,
  but no full Maven suite was run because Task 2 changes only standalone Node
  measurement code.

## Review follow-up: local polarity and supersession

### RED evidence

Ran `node --test scripts/rag-evidence-coverage.test.js` after adding focused
regressions for:

- `공개장소에 자유롭게 설치해서는 안 됩니다.`;
- `공개장소에 자유롭게 설치하면 안 됩니다.`; and
- `비대상이라는 견해도 있지만 실제로는 과업심의 대상입니다.`.

Result: exit 1; 6 passed, 3 failed. Each failing assertion incorrectly returned
the positive alias, proving that `안` was not recognized through local grammar
bridges and that a contrast/final assertion left the superseded earlier
proposition matchable.

### Fix

- Added generalized local polarity bridge scanning. It consumes Korean
  connective/conditional grammar such as `해서는` and `하면` before checking
  the following local negation.
- Added grammatical `안` recognition only when followed by common negative
  predicate morphology, avoiding a blanket lexical `안` substring rule.
- Added a generalized contrast plus final-assertion rule: assertions preceding
  `하지만`/`그러나`/`반면`/`지만`/`으나` are discarded only when followed by a
  finality marker such as `실제로는`, `사실은`, `결론적으로`, or `결국`.
- No case IDs, oracle-specific phrases, production imports, or runtime paths
  were added.

### GREEN evidence and self-review

Ran `node --test scripts/rag-evidence-coverage.test.js`.

- Result: exit 0; 9 passed, 0 failed.
- The two `안` forms and the contrast/final-assertion fixture now reject the
  positive aliases, while all earlier OR, material-token, item/sentence,
  polarity, normalization, and dotted-date tests remain green.
- Review confirmed the new rules work on local grammatical forms and discourse
  markers rather than case IDs, and `rag-explicit-oracle-matcher.js` remains
  evaluation-only and unimported by production Java/JavaScript. No runtime or
  18080 action was performed.

## Second review follow-up: complete marker variants

### RED evidence

Added focused regressions for all reported variants and ran
`node --test scripts/rag-evidence-coverage.test.js`.

- `공개장소에 자유롭게 설치할 경우 안 됨.`
- `공개장소에 자유롭게 설치했을 경우 안 됩니다.`
- `공개장소에 자유롭게 설치 안 됨.`
- `비대상이라는 견해도 있지만 실제로 과업심의 대상입니다.`
- `비대상이라는 견해도 있지만 오히려 과업심의 대상입니다.`

Result: exit 1; 9 passed, 5 failed. Each failure returned the positive alias,
confirming the conditional bridge, `안됨`, and finality-marker gaps.

### Fix and Java-list review

- Compared the Node local-polarity bridge list against the complete Java list:
  `이라고는`, `라고만`, `이라고`, `라는`, `라고`, `고`, `하고`,
  `다면서`, `하면`, `경우`, `하는경우`, `인경우`, `있을경우`,
  `정해서는`, `정할수`, `말할`, `보면`, `수`, `에서는`, `이라거나`,
  `라는것`, `것`, `반드시`, `아직`, `이미`, `으로`, `은`, `는`, `이`,
  `가`, `을`, `를`, `과`, `와`, `만`, and `지`.
- Retained every Java bridge and generalized conditional `경우` morphology for
  `할`, `했을`, `하는`, `인`, and `있을`; this avoids phrase-specific bridge
  exceptions while covering the reported conditional forms.
- Extended grammatical `안` recognition with the predicate morphology `됨`,
  while retaining morphology gating so lexical substrings are not blanket
  negations.
- Compared contrast markers (`하지만`, `그러나`, `반면`, `지만`, `으나`) and
  finality markers. The finality set now covers `실제로`, `실제로는`, `오히려`,
  `사실은`, `사실상`, `결론적으로`, `결국`, `정리하면`, and `요컨대`.
  These are discourse classes, not case or alias exceptions.

### GREEN evidence and self-review

Ran `node --test scripts/rag-evidence-coverage.test.js`.

- Result: exit 0; 14 passed, 0 failed.
- All five newly added variants reject `공개장소에 자유롭게 설치` or `비대상`
  as required; the prior nine focused matcher regressions remain green.
- Review confirmed no production imports, case IDs, oracle-specific branches,
  answer behavior, or runtime/18080 changes. The matcher stays evaluation-only.

## Third review follow-up: complete Java local-marker parity

### UTF-8 source review

Read the UTF-8 Java constants directly from
`src/main/java/com/kaces/pandora/ai/answer/ExplicitOracleTermMatcher.java`.

- `GRAMMATICAL_NEGATIONS`: source 8, Node normalized table 8, missing 0,
  extra 0: `안됩니다`, `안된다`, `안됨`, `아님`, `아니`, `않`, `없`, `불가`.
- `LOCAL_POLARITY_BRIDGES`: source 33, Node normalized table 33, missing 0,
  extra 0: `이라고는`, `라고는`, `이라고`, `라는`, `라고`, `다고`, `한다면`,
  `하면`, `할경우`, `하는경우`, `한경우`, `했을경우`, `단정해서는`,
  `단정할수`, `말할수`, `볼수`, `할수`, `해서는`, `이라는것은`, `라는것은`,
  `인것은`, `것은`, `반드시`, `절대`, `전혀`, `으로`, `은`, `는`, `이`,
  `가`, `도`, `만`, `지`.

`아닙` remains a separate generalized grammatical prefix required by the
reviewer; it is deliberately outside the two Java-parity tables above and does
not change either zero-difference count.

### RED evidence

Added table-driven tests that iterate every Java-equivalent negation and bridge
entry, plus the reviewer `아닙` form, and ran
`node --test scripts/rag-evidence-coverage.test.js`.

- Result: exit 1; 14 passed, 2 failed.
- The failures identified the missing Java negation `아님` and bridge `다고`;
  the table loops stop at their first missing entry, so the test establishes
  that absent markers cannot be hidden by the earlier example fixtures.

### GREEN evidence and self-review

Ran `node --test scripts/rag-evidence-coverage.test.js` after porting both
complete marker tables.

- Result: exit 0; 16 passed, 0 failed.
- The table-driven regressions now reject the positive alias for all 8
  grammatical negation markers, the reviewer `아닙` form, and all 33 local
  polarity bridges; all previous focused cases remain green.
- The Node tables were compared to the actual UTF-8 Java constants by count
  and set membership (0 missing, 0 extra for both families). No case IDs,
  production imports, answer behavior, runtime paths, or 18080 changes were
  introduced.

### UTF-8 parity correction

This correction supersedes the earlier transcription of the fourth Java
negation marker. The actual UTF-8 source marker is `아닙` (`U+C544 U+B2D9`),
not `아님` (`U+C544 U+B2D8`).

An independent raw source/target set comparison found the mismatch before
commit. The initial behavior loop still passed because the prior generalized
prefix handled `아닙`, so a static table-parity regression was added to prevent
hidden marker-table drift.

- RED: `node --test scripts/rag-evidence-coverage.test.js` exited 1 with
  16 passed and 1 failed; the assertion showed Node `아님` versus Java `아닙`.
- GREEN: after correcting the Node marker table, the same command exited 0
  with 17 passed and 0 failed.
- The final direct UTF-8 source/target set comparison reports
  `GRAMMATICAL_NEGATIONS source=8 target=8 missing=0 extra=0` and
  `LOCAL_POLARITY_BRIDGES source=33 target=33 missing=0 extra=0`.

## Final review follow-up: canonical Java-source parity

### RED evidence

Removed the duplicated Java marker literals from the Node test and changed it
to require a parser/export contract for the canonical UTF-8 Java source. Ran
`node --test scripts/rag-evidence-coverage.test.js` before adding that parser.

- Result: exit 1; 15 passed, 4 failed.
- Each failure reported `parseJavaListConstant` as `undefined`, proving the
  tests could no longer silently validate a duplicated Node-side fixture.

### Fix and GREEN evidence

- Added `parseJavaListConstant(source, name)` to the evaluation-only matcher.
  It finds the named Java `List.of(...)` constant, reads quoted strings with
  whitespace/comma validation, and safely decodes Java simple and Unicode
  escapes for these marker lists.
- Exported the two Node marker tables and the parser solely for measurement
  verification; no production answer code imports this module.
- The test now reads
  `src/main/java/com/kaces/pandora/ai/answer/ExplicitOracleTermMatcher.java`
  as UTF-8, extracts the canonical lists, compares source/target counts and
  sets, and reports explicit `missing`/`extra` values on mismatch. A deliberate
  mismatched fixture test verifies that failure path.

Ran `node --test scripts/rag-evidence-coverage.test.js` after the fix.

- Result: exit 0; 19 passed, 0 failed.
- Canonical source parity passes for both tables; no hardcoded `JAVA_*` marker
  tables remain in the test.
- Self-review: parser scope is restricted to the named Java constants and
  rejects malformed/unsupported literals. It remains evaluation-only, contains
  no case IDs, and does not touch production behavior, runtime paths, or 18080.
