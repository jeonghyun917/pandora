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

Start/stop the development app:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora.ps1 -Role app-dev -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-pandora.ps1 -Role app-dev -Port 8080
```

Promote a verified fat jar to batch runner:

```powershell
.\mvnw.cmd -DskipTests package
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\promote-batch-runner.ps1
```

Start/stop the batch runner:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora.ps1 -Role batch-runner -Port 18080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-pandora.ps1 -Role batch-runner -Port 18080
```

`law-ai.batch.scheduler-enabled` is disabled by default. `start-pandora.ps1`
enables it only for `batch-runner`, so the 8080 development runtime cannot
accidentally poll or ingest OpenAI Batch jobs.

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
