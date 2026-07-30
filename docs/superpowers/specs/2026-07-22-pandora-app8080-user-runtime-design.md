# Pandora App8080 User Runtime Design

## Goal

Allow Codex to deploy and restart only Pandora app-dev on port 8080 without
repeated UAC prompts, while preserving the protection around batch-runner 18080
and Qdrant 6333.

## Root cause

Codex commands run as `고정현\CodexSandboxOffline`. The installed
`PandoraApp8080` Windows service runs as `LocalSystem`. Its default service DACL
lets interactive users query status but does not grant `SERVICE_START` or
`SERVICE_STOP`, so non-elevated stop/start attempts fail.

Granting those two rights is unsafe in the current layout. The service wrapper,
XML configuration, and application JAR are below the Codex-writable workspace.
An identity that can replace the JAR and start a `LocalSystem` service can cause
arbitrary modified code to execute as `LocalSystem`. Microsoft explicitly warns
that service control rights granted to an untrusted identity can interfere with
or subvert a service running under a privileged account.

References:

- [Service Security and Access Rights](https://learn.microsoft.com/en-us/windows/win32/services/service-security-and-access-rights)
- [Configuring a Service Using SC](https://learn.microsoft.com/en-us/windows/win32/services/configuring-a-service-using-sc)

## Considered approaches

### 1. Grant the Codex SID service start/stop rights

Rejected. The ACL would be narrow by service name, but the writable executable
chain makes it an effective local privilege-escalation path.

### 2. Install an elevated scheduled task or run Codex as administrator

Rejected. Both grant a much broader execution surface than the 8080 workflow
needs, especially when task inputs or scripts are workspace-writable.

### 3. Retire the LocalSystem app-dev service and run 8080 as the Codex user

Selected. One elevated transition stops and disables only
`PandoraApp8080`. Future app-dev deployment uses the existing repository
process launcher under the non-administrative Codex identity. Modified app code
therefore runs with the same privileges as the identity that modified it.

A dedicated Windows virtual service account could provide background service
semantics later, but it requires separate file/secret ACL design and is not the
minimum safe solution for a development runtime.

## Architecture

### One-time transition

`scripts/set-pandora-app8080-user-runtime.ps1` is hard-coded to
`PandoraApp8080` and the expected wrapper path. `-Action Prepare`:

1. Reads and records the current 8080 service path, account, status, and start
   mode in an ignored JSON manifest.
2. Refuses if the service name, executable path, or account is unexpected.
3. Requires an elevated token before mutation.
4. Stops only `PandoraApp8080` through the repository service script.
5. Changes only that service start mode to `Disabled`.
6. Verifies port 8080 is no longer listening and verifies the observed 18080
   state did not change.

`-Action Restore` restores the recorded start mode and running state, but is
also elevated and explicit. It is a rollback path, not part of normal work.

### Regular deployment

`scripts/deploy-pandora-app8080.ps1` has no role, port, or service-name
parameter. It:

1. Requires `PandoraApp8080` to be stopped and disabled.
2. Validates the staged fat JAR and optional trusted SHA-256.
3. Stops only a user-owned direct process listening on 8080 through
   `scripts/stop-pandora.ps1`.
4. Copies the staged JAR to the app-dev target and verifies source/deployed
   SHA-256 equality.
5. Starts only app-dev 8080 as a non-elevated Java child and keeps the command
   session alive as its supervisor.
6. Waits for the runtime-info endpoint and verifies runtime status and JAR hash.
7. Compares before/after 18080 and 6333 observations and fails if they differ.

The supervisor must remain running because the Codex sandbox closes detached
child processes when their command session exits. A normal repository stop
command terminates the supervised Java child, after which the supervisor cleans
the PID file and exits.

The scripts never accept `18080`, `batch-runner`, `PandoraBatch18080`, or a
Qdrant mutation target as input.

## Testing and verification

- A PowerShell test first records RED while the scripts are absent.
- Dry-run tests prove no service, process, port, or JAR state changes.
- Focused tests cover service identity validation, SHA-256 validation, and the
  hard-coded app-dev scope.
- Self-review checks that no command can be redirected to 18080.
- The one-time transition is followed by non-elevated 8080 deploy/start/stop
  verification.
- `scripts/status-pandora.ps1` snapshots confirm 18080 and Qdrant are untouched.
- The existing backend/full RAG verification remains required before quality
  completion is claimed.

## Operational trade-off

Port 8080 no longer starts automatically as a Windows service. This is suitable
for app-dev: Codex starts it on demand. The batch-runner lifecycle remains
independent and protected.
