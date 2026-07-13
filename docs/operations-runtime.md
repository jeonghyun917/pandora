# Pandora Runtime And Release Rules

This document is the operational contract for local/dev runtime and batch runner deployment.

## Runtime Roles

| Role | Port | Purpose | Restart Rule |
| --- | ---: | --- | --- |
| app-dev | 8080 | Browser UI, API verification, RAG logic development | Can be restarted by the active development thread |
| batch-runner | 18080 | Batch poll, ingest, queue fill, long-running indexing jobs | Restart only after confirming no active ingest/poll job, or after explicit operator approval |
| qdrant | 6333 | Vector store REST API | Restart only after snapshot or explicit approval |

## Build Artifact Rule

There should be one application build artifact:

```text
target/pandora-0.0.1-SNAPSHOT.jar
```

The batch runner uses a promoted copy:

```text
runtime/batch/pandora-batch-runner.jar
```

Do not copy a jar smaller than 10 MB into `runtime/batch`. A small jar usually means Maven `spring-boot:repackage` did not complete and the file is not a runnable Spring Boot fat jar.

When `PandoraApp8080` is running, do not run a normal `mvn package` against
`target/pandora-0.0.1-SNAPSHOT.jar`. Spring Boot repackaging replaces that
archive in place, which can fail on Windows because the active service holds it.
Build the replacement jar in the ignored staging directory first:

```powershell
.\mvnw.cmd -Papp-dev-staged-package -DskipTests package
```

This produces `target-stage/pandora-0.0.1-SNAPSHOT.jar`. Stop only the 8080
service, verify the staged jar, replace the app-dev jar, then start 8080 again.
The 18080 batch runner must not be stopped or promoted as part of this flow.

Batch-runner process metadata is kept separately from the promoted jar:

```text
runtime/batch-runner/pandora-18080.pid
runtime/batch-runner/logs/
```

## Standard Commands

Check current state:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-pandora.ps1
```

Start the development app in a visible console:

```powershell
.\scripts\start-pandora-console.cmd -Role app-dev -Port 8080
```

This is the preferred local development path on PCs where hidden PowerShell,
Task Scheduler, or VBS launchers are blocked by endpoint security. Keep the
console window open while using the app. Closing the console stops the app.

Stop the development app from another console when needed:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-pandora.ps1 -Role app-dev -Port 8080
```

Legacy hidden start remains available, but it is not the recommended path on
the current workstation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora.ps1 -Role app-dev -Port 8080 -UseJar
```

Promote a verified fat jar to batch runner:

```powershell
.\mvnw.cmd -DskipTests package
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\promote-batch-runner.ps1
```

Start/stop the batch runner only after explicit operator approval:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora.ps1 -Role batch-runner -Port 18080 -ConfirmBatchRunner
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-pandora.ps1 -Role batch-runner -Port 18080
```

`law-ai.batch.scheduler-enabled` is disabled by default. `start-pandora.ps1`
enables it only for `batch-runner`, so the 8080 development runtime cannot
accidentally poll or ingest OpenAI Batch jobs.

## Windows Service Option

Use Windows services only through a service wrapper. Do not register `java.exe`
directly with `sc.exe`; the Java process does not implement the Windows service
control protocol by itself.

Pandora service scripts use WinSW when a wrapper executable is available at
`tools\winsw\WinSW.exe`, through `PANDORA_WINSW_EXE`, or through `-WinSWExe`.
The scripts fail closed when the wrapper is missing.

Render service configuration without installing:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action RenderConfig -Role app-dev -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action RenderConfig -Role batch-runner -Port 18080
```

Install and start the 8080 app service after placing WinSW:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Install -Role app-dev -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Start -Role app-dev -Port 8080
```

The default service start mode is `Manual`, so the app does not start on every
Windows boot unless `-StartMode Automatic` is explicitly used during install.

## Service-only Secrets

Windows services do not inherit the interactive user's `OPENAI_API_KEY`. For a
local service runtime, create the ignored service-only properties file from the
current user environment:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\set-pandora-service-secrets.ps1 -Role app-dev -Port 8080 -FromCurrentUserEnvironment
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action RenderConfig -Role app-dev -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Restart -Role app-dev -Port 8080
```

The generated `runtime\app-dev\pandora-service.properties` file is ignored by
Git. It contains the service-only OpenAI key and is ACL-restricted to the local
user, `SYSTEM`, and local administrators. Do not commit it or place the key in
`application.properties`.

Mutating the 18080 batch service requires explicit confirmation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Start -Role batch-runner -Port 18080 -ConfirmBatchRunner
```

## Admin And Debug API Protection

The following API families are protected by `pandora.admin-access`:

- `/api/admin/**`
- `/api/law-data/ai/debug/**`
- `/api/rag-collection/**`
- mutation/batch endpoints under `/api/law-data/semantic/**`

Default policy:

```properties
pandora.admin-access.enabled=true
pandora.admin-access.local-only=true
pandora.admin-access.token=${PANDORA_ADMIN_TOKEN:}
```

Remote admin access requires `X-Pandora-Admin-Token` when `PANDORA_ADMIN_TOKEN` is configured.

## Backup Rule

Before destructive index maintenance, major reindexing, or Qdrant cleanup:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\create-qdrant-snapshots.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-mariadb.ps1 -Password <db-password>
```

The backup manifest and SQL dump are written below:

```text
runtime/backups/
```

## Release Gate

Before promoting a new jar:

```powershell
.\mvnw.cmd test
node .\scripts\rag-eval-gate.js
```

The eval gate writes:

```text
logs/rag-eval-gate-latest.json
logs/rag-eval-gate-latest.md
```

The gate must pass before the jar is promoted to the batch runner.
