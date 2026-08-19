# Task 15 lexical rebuild failure recovery

## Preserved failure evidence

- Failed shadow revision: `10539747-5f55-4350-9577-0db4e7c0dd0f`
- Tokenizer: `korean-lexical-v1`
- State at failure: `BUILDING` (never published as `READY`)
- Inserted chunk rows: `215500`
- Approximate inserted term rows from `information_schema`: `34343759`
- Approximate term table size: `7998.0 MiB`
- Previous ready revision: none
- Deployed app JAR SHA-256: `73163544E8C74A30132A6B198A7FF5C53DE2666D9C93E9785D0EAFCEC23DA556`
- Runtime impact: none; `findReadyRevision` excludes the failed `BUILDING` revision, so legacy retrieval remained authoritative.

The failing insert was for `official_doc` chunk `88490`. The source body contained
both `U+302E U+C7A5 U+C560 U+C0C1 U+D669` (`U+302E` followed by `장애상황`)
and plain `U+C7A5 U+C560 U+C0C1 U+D669` (`장애상황`) in the same field.
Java treated these as distinct strings because `U+302E` is in the Hangul Unicode
block. MariaDB `utf8mb4_unicode_ci` treated them as the same primary-key value.

The reproduction returned:

- `utf8mb4_unicode_ci` equality: `1`
- rows stored after changing the term column to `utf8mb4_bin`: `2`
- resulting term collation: `utf8mb4_bin`

## Recovery change

Commit `88716803`:

1. treats Hangul tone marks `U+302E` and `U+302F` as token separators;
2. bumps the tokenizer revision to `korean-lexical-v2`;
3. uses binary collation for lexical term identity and migrates only when needed;
4. marks a rebuild `FAILED` if any build stage throws, without overwriting a terminal state.

Verification before runtime deployment:

- focused lexical tests: passed;
- full Java suite: `1178` run, `0` failures, `0` errors, `18` skipped;
- Node tool suite: `129` passed, `0` failed;
- MariaDB scratch-schema collation reproduction: passed;
- `git diff --check`: clean before commit.

## Cleanup boundary

The four `semantic_lexical_*` tables contain only this unpublished failed shadow
revision. With app-dev stopped and port `18080` untouched, they may be dropped in
foreign-key order and recreated by the fixed schema maintenance code. Law/RAG
source data, MariaDB embedding state, and both Qdrant collections are outside this
cleanup boundary.
