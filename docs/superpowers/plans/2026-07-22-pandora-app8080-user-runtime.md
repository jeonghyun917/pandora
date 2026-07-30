# Pandora App8080 User Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace repeated UAC-controlled `LocalSystem` app-dev restarts with a one-time safe transition and non-elevated, user-owned 8080 deployment workflow.

**Architecture:** An elevated, hard-coded transition script stops and disables only the existing `PandoraApp8080` LocalSystem service and records rollback state. A separate non-elevated deployment script verifies and copies the staged fat JAR, then uses the repository's existing app-dev process launcher; neither script accepts a batch role, 18080, or Qdrant mutation input.

**Tech Stack:** Windows PowerShell 5.1, Windows Service Control Manager, WinSW, Java 17, Spring Boot, repository runtime scripts.

## Global Constraints

- Never grant `CodexSandboxOffline` start rights on a workspace-writable LocalSystem service.
- Mutate only `PandoraApp8080` and port 8080.
- Never stop, restart, configure, promote, or grant rights on batch-runner 18080.
- Never stop, restart, reconfigure, or grant rights on Qdrant 6333.
- Preserve all existing dirty and untracked work; do not reset, revert, clean, stage, or commit.
- Use repository runtime scripts for normal 8080 process control.
- Record RED, implement the minimum generalized change, record GREEN, self-review, then run full applicable verification.

---

### Task 1: Scope and dry-run tests

**Files:**
- Create: `scripts/tests/pandora-app8080-user-runtime.tests.ps1`

**Interfaces:**
- Consumes: the repository root, current runtime snapshot, and the staged JAR.
- Produces: exit code 0 only when dry-run execution preserves 8080, 18080, Qdrant, service, and deployed-JAR state.

- [ ] **Step 1: Write the failing test**

Create a standalone PowerShell test harness that captures service status, port
listeners, and deployed JAR hash; invokes both planned scripts with `-DryRun`;
then asserts that every captured value is unchanged. It must also assert that
the scripts expose no role, port, or service-name override parameter.

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\pandora-app8080-user-runtime.tests.ps1
```

Expected: FAIL because `set-pandora-app8080-user-runtime.ps1` and
`deploy-pandora-app8080.ps1` do not exist.

### Task 2: One-time safe service transition

**Files:**
- Create: `scripts/set-pandora-app8080-user-runtime.ps1`
- Test: `scripts/tests/pandora-app8080-user-runtime.tests.ps1`

**Interfaces:**
- Consumes: `-Action Prepare|Restore`, `-ProjectDir`, `-DryRun`, and an elevated token for mutation.
- Produces: ignored JSON rollback manifest and a stopped/disabled `PandoraApp8080` service for `Prepare`.

- [ ] **Step 1: Implement the minimum transition**

Hard-code these invariants and fail before mutation when any mismatch occurs:

```powershell
$serviceName = 'PandoraApp8080'
$expectedServicePath = Join-Path $ProjectDir 'runtime\services\PandoraApp8080\PandoraApp8080.exe'
$expectedServiceAccount = 'LocalSystem'
```

`Prepare` saves service state, requires elevation, stops the service using
`scripts/start-pandora-service.ps1 -Action Stop -Role app-dev -Port 8080`, sets
start mode to `Disabled`, and waits for stopped status and closed port 8080.
`Restore` reads the manifest, restores its start mode, and starts the service
only when the manifest says it was originally running. `-DryRun` performs all
validation and prints planned operations without mutation or elevation.

- [ ] **Step 2: Run the focused test**

Run the Task 1 command. Expected: transition dry-run checks pass; deployment
checks still fail because the deployment script is absent.

### Task 3: Non-elevated 8080 deployment

**Files:**
- Create: `scripts/deploy-pandora-app8080.ps1`
- Modify: `docs/operations-runtime.md`
- Test: `scripts/tests/pandora-app8080-user-runtime.tests.ps1`

**Interfaces:**
- Consumes: `-ProjectDir`, `-StagedJar`, optional `-ExpectedSha256`, and `-DryRun`.
- Produces: verified app-dev target JAR and a user-owned process listening on 8080.

- [ ] **Step 1: Implement the minimum deployment**

Validate `PandoraApp8080` is stopped/disabled; reject files below 10,000,000
bytes; normalize and validate any expected SHA-256 against `^[0-9A-F]{64}$`;
compute source hash; stop only a direct 8080 process via
`scripts/stop-pandora.ps1 -Role app-dev -Port 8080`; copy; verify destination
hash; start via `scripts/start-pandora.ps1 -Role app-dev -Port 8080 -UseJar`;
then wait for the 8080 health endpoint. Capture 18080 service/port state before
and after and fail if it changes. `-DryRun` stops after validation and prints
the exact plan.

- [ ] **Step 2: Document the safe workflow**

Add the security reason, one-time command, normal deploy command, rollback
command, and the unchanged 18080 rule to `docs/operations-runtime.md`.

- [ ] **Step 3: Run focused tests to verify GREEN**

Run the Task 1 command. Expected: all assertions pass and runtime state is
unchanged.

### Task 4: Review and live verification

**Files:**
- Review: `scripts/set-pandora-app8080-user-runtime.ps1`
- Review: `scripts/deploy-pandora-app8080.ps1`
- Review: `scripts/tests/pandora-app8080-user-runtime.tests.ps1`
- Review: `docs/operations-runtime.md`

**Interfaces:**
- Consumes: the tested scripts and current staged JAR.
- Produces: a verified one-time transition and non-elevated 8080 runtime.

- [ ] **Step 1: Self-review**

Inspect the diff for any generic service/role/port mutation input, any use of
`PandoraBatch18080`, any Qdrant mutation, any writable elevated scheduled task,
and any unvalidated service executable path. Fix every finding before live use.

- [ ] **Step 2: Run the one-time elevated transition**

Launch `set-pandora-app8080-user-runtime.ps1 -Action Prepare` with
`Start-Process -Verb RunAs -Wait`. This is the single expected UAC prompt.
Verify `PandoraApp8080` is stopped and disabled.

- [ ] **Step 3: Deploy the staged JAR without elevation**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-pandora-app8080.ps1 -StagedJar .\target-stage\pandora-0.0.1-SNAPSHOT.jar -ExpectedSha256 AABFBDDF6EA13EDB0AE83C85A099B8B1E61AD4230819130D0E54A0779F68E4F7
```

Expected: source and deployed hashes match; a non-elevated Java process listens
on 8080; 18080 and 6333 snapshots are unchanged.

- [ ] **Step 4: Run focused runtime verification**

Run the deployment script once more and verify it can stop, replace, and restart
the user-owned 8080 process without UAC. Check the runtime-info and health
endpoints.

- [ ] **Step 5: Run full applicable verification**

Run `powershell -File scripts/status-pandora.ps1`, the existing focused RAG case
set, and `node .\scripts\rag-eval-gate.js` when the live runtime identity is
stable. State explicitly if the complete 1,004-case gate is not run in this
session.
