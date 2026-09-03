# Group-balanced BM25 full release gate — fail-closed checkpoint

Date: 2026-09-03 (Asia/Seoul)

## Decision

- Gate: `FAIL_CLOSED`
- Valid checkpoint cases: 10 / 1,003
- Passed: 4
- Failed: 6
- Request errors: 0
- Qdrant search failures: 0
- Promotion decision: rejected; lexical variant authority must return to shadow-only mode.

The release policy requires zero blocking failures. Six blocking failures in the
first ten durable results made the release outcome irreversible, so the run was
stopped before sending the remaining 993 questions.

## Blocking cases

- `project-review-hardware-exclusion`: direct answer evidence was found, but a
  forbidden committee-meeting chunk was also selected.
- `project-review-sns-operation`: answer verification failed.
- `project-review-pre-consultation-relation`: answer verification failed with
  two unsupported claims and did not keep the two regimes separated safely.
- `pre-consultation-exception`: answer verification failed with unsupported
  threshold and inclusion claims.
- `security-review-target`: answer verification failed.
- `security-review-exception`: the selected grounds did not support the required
  system-access exception and condition groups; five answer claims were not
  supported by the selected grounds.

## Provenance

- Git commit at evaluation start: `c85bec15`
- Runtime JAR SHA-256:
  `a4b473a71606cc15f88af1c7b3beae13c31702048cfda03f5b4832ce6787480d`
- Runtime instance: `df47a26e-6630-46f0-8ccf-f8143a9c0cdf`
- Runtime config SHA-256:
  `6db6b2f969c26a5f0dcc1d5148e84d50a1059812cee1580a0ed69f589ffe8ef6`
- Index revision:
  `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- Lexical revision:
  `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`
- Law DB / Qdrant: `211548 / 211548`
- RAG DB / Qdrant: `84248 / 84248`
- Checkpoint SHA-256:
  `0e825b94492235e4a9edb917c5be718046f37d431a52673df9c6fc2f021af091`

## Timeout incident

The initial run used ten cases per HTTP request and a 180-second client timeout.
The server completed all ten cases in about 292 seconds, after the client had
closed the connection, so that attempt produced no checkpoint and was not used
as release evidence. The retry used one case per request with a durable
checkpoint after every response. This operational retry was within the approved
evaluation-error retry allowance. The retry was stopped after ten durable
results because the zero-failure release criterion was already violated.

## Evidence

- `docs/evidence/rag-quality/group-balanced-bm25-full-release-fail-c85bec15-checkpoint.json`

