# Task 15 document-first expansion checkpoint

Date: 2026-08-24 (Asia/Seoul)

- Candidate branch: `codex/document-first-candidate-expansion`
- Verified code commit/tree:
  `04dbf342c3f113419b67735358d1f3de0748cfd1` /
  `70c21dca46a9414d76fd0dc1b9e1c6449dd9d145`
- Verification: focused Maven `107/107`; Node `111/111`; full Maven `1301`
  tests, failures/errors `0/0`, environment-only skips `18`.
- Document expansion is enabled only as a bounded `3/8/24` shadow;
  document-expansion, RRF, coverage-aware, and semantic authority remain off.
- No external evaluation, OpenAI request, Qdrant mutation, deployment, or
  service lifecycle action was performed. Port `18080` and `output/` were not
  touched.
- Live MyBatis/MariaDB mapper execution remains a Task 9 pre-evaluation fence
  because app-dev was stopped and no safe read-only mapper harness was present.
- Evidence:
  `docs/superpowers/specs/2026-08-24-document-first-candidate-expansion-verification.md`.
- Next: prepare an immutable Task 15 training manifest, obtain exact external
  payload approval, recheck all runtime/index/config/parity fences, then launch
  at most once.
