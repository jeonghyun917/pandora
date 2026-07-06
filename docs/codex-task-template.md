# Codex Task Template

Use this instead of pasting a very long quality charter into every request.

## General Implementation Request

```text
Goal:
[One concrete outcome.]

Scope:
[Files, feature area, or behavior to change.]

Do not touch:
[Runtime, data, ports, or modules that must stay unchanged.]

Verification:
- Run focused tests for the changed area.
- Run full tests or explain why they were not run.
- If RAG behavior changes, run the relevant eval gate cases.

Completion:
Summarize changed files, verification results, and remaining risks.
```

## RAG Accuracy Request

```text
Goal:
Reduce wrong or unsupported RAG answers for this failure class.

Failure examples:
- [Question]
- Expected evidence: [document/page/section/terms]
- Bad behavior: [wrong evidence, no grounds, unsupported answer, etc.]

Requirements:
- Inspect the current retrieval, reranking, evidence judge, claim verifier, and
  failure logging flow before editing.
- Prefer a general fix over a one-question patch.
- Add or update evaluation cases for the failure class.
- Run focused tests and the relevant RAG eval gate cases.

Completion:
Report the root cause, code/data changes, eval results, and remaining risks.
```

## Runtime Or Deployment Request

```text
Goal:
[Start, stop, rebuild, promote, or inspect runtime.]

Runtime rules:
- 8080 is app-dev.
- 18080 is batch-runner.
- Do not touch 18080 unless this request explicitly says so.

Verification:
- Run scripts/status-pandora.ps1 before and after.
- Confirm the expected port is listening.
- Confirm the health/API check used.
```

## Research Request

```text
Question:
[What to verify.]

Rules:
- Search the web or official documentation.
- Separate confirmed facts from recommendations or plans.
- Cite sources.
- Do not speculate when the source does not confirm it.
```
