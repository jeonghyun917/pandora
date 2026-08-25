# Task 8 verification report

Date: 2026-08-24 (Asia/Seoul)

## Scope

Verified the bounded document-first shadow implementation, documented the
quality gate and Task 15 checkpoint, and performed no runtime mutation or
external evaluation.

## Commands and results

1. Focused Maven (the 11 exact test classes from Task 8): `107` tests, failures
   `0`, errors `0`, skipped `0`.
2. Relevant Node evaluator/selector/provenance suite: `111` tests, pass `111`,
   fail/cancelled/skipped/todo `0/0/0/0`.
3. Full `./mvnw.cmd test`: `1301` tests, failures `0`, errors `0`, skipped
   `18`. The skips are the opt-in MariaDB repair-operation integration class.
4. `git diff --check`: no whitespace errors. Git emitted only the repository's
   normal LF-to-CRLF working-copy warning for two existing Markdown files.
5. `scripts/status-pandora.ps1`: app-dev service stopped/disabled; stale 8080
   and 18080 PID files; batch service not installed; no local LISTENING entry
   for 8080, 18080, or 6333. No lifecycle action was taken.
6. `Get-Service MariaDB`: running. No live mapper invocation was attempted
   because the candidate app-dev runtime was stopped and no safe read-only
   MyBatis integration harness exists.

The first focused Maven attempt did not start Maven because unquoted commas in
the `-Dtest` value were parsed by PowerShell. The same exact value was rerun as
one quoted argument. A sandboxed Maven attempt then failed before project
resolution because Maven Central network access was denied; the exact command
was rerun with approved network access and produced the passing result above.

## Identity and safety evidence

- Verified production/test commit:
  `04dbf342c3f113419b67735358d1f3de0748cfd1`
- Verified production/test tree:
  `70c21dca46a9414d76fd0dc1b9e1c6449dd9d145`
- Committed-default configuration SHA-256:
  `c4c561172e2864f6215698bc002095ebe73b636a06d86057bc0dfc086620504c`
- Document expansion: enabled shadow, authoritative `false`, bounds `3/8/24`.
- RRF authoritative `false`; coverage-aware disabled; semantic verification and
  selection authoritative flags `false`.
- No OpenAI call, Qdrant request/mutation, candidate deployment, service
  start/stop/restart, 18080 action, or `output/` access occurred.

## Self-review

No Critical or Important scope violation found. Production code is independent
of evaluation oracle fields; mapper/service/answer boundaries enforce exact
bounds; control orders and external call counts remain unchanged while
authority is false; new trace fields are bounded metadata; failures fall back
to baseline; and the selector requires frozen baseline plus two-run provenance
and non-regression gates.

## Concern retained for Task 9

Task 3's live MariaDB mapper execution is not closed in this stopped-runtime
environment. XML parsing/rendering/binding tests pass, but Task 9 must execute a
read-only candidate-runtime preflight before any external evaluation. Task 9
also remains hard-gated on a fresh immutable manifest and exact OpenAI payload
approval.
