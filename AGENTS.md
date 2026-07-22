# Pandora Codex Instructions

These instructions apply to the whole repository.

## Default Goal

Pandora is a law/admin-rule/document RAG application. The top quality goal is to
avoid unsupported or wrong answers. Prefer refusing, asking for clarification, or
logging a searchable failure over producing a plausible but ungrounded answer.

## Scope Control

- Convert broad requests into a bounded plan before making large changes.
- Do not interpret "finish everything" as permission for unrelated rewrites.
- Keep each implementation step reviewable: inspect, edit, test, self-review,
  then continue.
- If a task discovers a bigger architecture issue, state the issue and handle the
  smallest safe slice that moves the system toward the agreed design.

## Superpowers Workflow

- Automatically use the relevant installed Superpowers skills for feature
  design, behavior changes, bug fixes, refactoring, RAG quality changes, and
  material code review. The user does not need to mention Superpowers explicitly.
- Use `superpowers:brainstorming` before designing new behavior,
  `superpowers:systematic-debugging` before proposing a bug fix,
  `superpowers:test-driven-development` for code features and bug fixes, and
  `superpowers:verification-before-completion` before claiming completion.
- Use the smallest workflow that fits the task. Check for relevant skills, but
  do not force a full feature-development lifecycle onto simple explanations,
  status checks, or docs-only corrections.
- An explicit `$superpowers:...` request overrides automatic skill selection.
- Superpowers improves process discipline; it does not guarantee the absence of
  hallucinations and does not replace the RAG Quality Rules below. Ground claims,
  verify evidence, and fail closed when direct support is missing.

## Runtime Contract

Use the documented runtime split:

- `8080`: app-dev, UI/API verification, RAG logic development.
- `18080`: batch-runner, long-running batch poll/ingest/indexing.
- `6333`: Qdrant.

Rules:

- Do not restart or stop `18080` unless the user explicitly asks or the batch
  owner has approved it.
- Use `scripts/status-pandora.ps1` before changing runtime state.
- Use `scripts/start-pandora.ps1` and `scripts/stop-pandora.ps1` instead of
  ad-hoc hidden windows.
- Build one main jar in `target/`. Promote to the batch runner only with
  `scripts/promote-batch-runner.ps1`.
- See `docs/operations-runtime.md` and `docs/runtime-ports.md` for commands.

## RAG Quality Rules

Follow `docs/rag-quality-gate.md`.

- Retrieve broadly: vector, keyword, title, section, parent context, and synonym
  expansion may all contribute candidates.
- Select narrowly: final grounds must directly answer the user question.
- Use supporting evidence only after at least one direct ground exists.
- Verify claims before returning an answer. Unsupported obligation, exception,
  amount, deadline, sanction, eligibility, or numeric claims must be removed or
  downgraded.
- Fail closed when direct grounds are missing.
- Store failed questions with stage and failure type so they can be reviewed and
  promoted into the evaluation set.

## Verification

For code changes:

- Run focused tests for the touched area.
- Run `.\mvnw.cmd test` before considering backend changes complete when
  practical.
- For RAG behavior changes, run `node .\scripts\rag-eval-gate.js` or a targeted
  eval gate with explicit case IDs. State clearly when the full gate was not run.
- For frontend changes, run the frontend build when the change can affect build
  output.

For docs-only changes:

- No build is required unless the docs reference executable commands or generated
  artifacts that need validation.

## Safety And Maintainability

- Preserve user changes. Never reset or revert unrelated dirty work.
- Avoid one-off question-specific patches when a general intent, dictionary,
  retrieval, judge, or verifier rule can solve the class of failures.
- Keep dictionaries and evaluation cases maintainable. Prefer configuration or
  data files over hard-coded special cases when the concept will grow.
- Do not expose secrets, API keys, personal contact data, or debug/admin details
  in user-facing answers.
- Protect debug/admin endpoints according to `docs/operations-runtime.md`.

## External Facts

When current external facts are needed:

- Use web or official documentation sources.
- Separate confirmed facts from plans or recommendations.
- Cite sources.
- Do not invent unsupported details.

## Final Response Shape

Keep final responses short and concrete:

- What changed.
- What was verified.
- What remains risky or not yet run.
- Any next step that follows directly from the work.
