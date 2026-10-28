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

## App-dev 8080 Without Repeated UAC

Do not grant the Codex or interactive development identity start rights on the
current `PandoraApp8080` service. That service runs as `LocalSystem`, while its
WinSW configuration and application JAR are in the developer-writable
workspace. Write access to the executable chain plus service start access would
allow modified code to run as `LocalSystem`.

For app-dev, use a one-time transition from the LocalSystem service to a
non-elevated, user-owned process. First inspect the plan without changing state:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\set-pandora-app8080-user-runtime.ps1 -Action Prepare -DryRun
```

Then run the transition. The script requests one UAC approval, verifies the
fixed `PandoraApp8080` name/path/account, stops only that service, and changes
only its start mode to `Disabled`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\set-pandora-app8080-user-runtime.ps1 -Action Prepare
```

The pre-transition state is saved below the ignored app-dev runtime directory:

```text
runtime/app-dev/PandoraApp8080-user-runtime-transition.json
```

After the transition, deploy and restart 8080 without elevation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-pandora-app8080.ps1 `
  -StagedJar .\target-stage\pandora-0.0.1-SNAPSHOT.jar `
  -ExpectedSha256 <64-character SHA-256>
```

The deploy script has no role, port, or service-name override. It controls only
app-dev 8080, validates the staged/deployed/runtime-reported JAR hashes, and
verifies that observed 18080 and 6333 state did not change.

The command remains running as the supervisor for the user-owned Java process.
Keep its Codex terminal session open while 8080 is needed. This is required
because the Codex sandbox terminates detached child processes when their command
session ends. Stop the supervised runtime from another terminal with the normal
app-dev command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-pandora.ps1 -Role app-dev -Port 8080
```

Rollback to the recorded Windows service state also requires UAC and is
explicit:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\set-pandora-app8080-user-runtime.ps1 -Action Restore
```

This workflow intentionally means app-dev 8080 starts on demand rather than at
Windows boot. It does not change the batch-runner 18080 or Qdrant 6333 lifecycle.

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

## RAG Lexical Index Migration

The application creates the exact-term lexical index schema at startup. Existing
RAG chunks continue to use the bounded legacy lexical query while the index is
`BUILDING`; indexed lookup becomes active only after every searchable current
chunk has a completion marker matching its content hash.

After deploying a build that introduces or changes the lexical index, backfill it
against the app-dev instance:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backfill-rag-search-index.ps1
```

The command is idempotent. Do not promote the build to the batch runner merely to
perform this migration. For a non-local deployment, set `PANDORA_ADMIN_TOKEN` or
pass `-AdminToken`; the script sends it as `X-Pandora-Admin-Token`.

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

## Isolated RAG document recovery

The admin panel's document recovery section uses `/api/law-data/rag-recovery`.
It requires a normal authenticated `ADMIN` session even when general authentication
is disabled. GET `session` supplies a session-bound CSRF value; POST `create`,
`validate`, `prepare`, `promote`, and `rollback` require
`X-Pandora-Recovery-CSRF`. Cross-origin mutations are rejected. Do not copy login
credentials, cookies, or tokens into scripts or evidence.

Recovery mutations are closed by default. The server settings are
`pandora.rag.recovery.product-sha` (the verified full 40-character product commit)
and `pandora.rag.recovery.mutations-enabled` (default `false`). A product SHA is
not proof that operational gates passed. Keep mutations disabled until isolated
real-Q verification, baseline DB/Q preservation, complete routing capability,
compatible corpus writers, source validation, quota/network checks, and deployment
health have all been established. A closed-gate app deployment is not a recovery
or a quality-gate PASS. Do not promote this build to the batch runner merely to
make its writer compatible; batch promotion retains its separate quality gate.

The supported order is preview/status, inactive candidate creation, validation,
isolated preparation/readback, and exact-revision promotion. The UI never chains
these writes automatically. Source/manifest/revision changes require fresh
evidence; an old terminal failure must not be blindly replayed. Rollback restores
the registered preserved baseline, not arbitrary candidate IDs, and uses a fresh
immutable full-corpus lexical namespace. Existing chunks and vector/keyword assets
are not deleted by pointer promotion or rollback.

Ordinary ingestion and law activation update a transactional corpus revision.
After recovery has been published, new requests fail closed if that corpus has
changed. The normal complete lexical rebuild retains captured recovery replacements
and publishes a new namespace only if both routing and corpus revisions still match.
Do not manually edit revisions, generation memberships, integrity receipts, or READY
states. Previously captured requests keep their immutable namespaces. Active vector
and lexical cardinality checks also run before cached answers can be returned;
they are availability checks, not a claim of distributed atomicity or protection
against arbitrary out-of-band asset replacement.

If a recovery test script is rejected by host policy, leave that branch blocked.
Do not change ExecutionPolicy, use Bypass/IEX/inline replacement, or rerun the same
denied operation through another engine. An administrator must provide a
policy-compliant execution route; user approval alone does not alter host policy.

## Preserved law/admin-rule source versions

`/api/law-data/source-versions/**` requires the same normal ADMIN session,
same-origin CSRF protection, and no-store responses as document recovery. Never
extract login inputs, cookies, or tokens into automation. The server fetches the
exact official search identity and detail itself: `law` uses MST, `admrul` uses ID.
Detail title, effective date and any supplied serial must agree with the search
source before group registration; a parser-generated fallback ID is not authority.

The opt-in wiring flag is `pandora.source-version.workflow-enabled` (default
false), with the existing full `pandora.rag.recovery.product-sha`. The independent
`pandora.source-version.mutations-enabled` flag defaults false. Keep both mutation
gates closed until actual isolated Q verification, complete baseline DB/Q health,
source/reference fidelity, quota/network availability, and compatibility/ownership
of **every** shared corpus/Q writer (including 18080) have been established. Do not
stop or promote 18080 to satisfy this condition without its separate authority.
A guarded app-dev deployment is not an actual source refresh or quality PASS.

API order: `status` / `session`, POST `stage` (baselineDocumentId,
expectedRevision, externalId), `validate` / `prepare` (candidateId), then `promote`
(transitionId, expectedRevision). `prepare-rollback` accepts only registered
history (groupKey, expectedRevision, restoreDocumentId); `rollback` applies that
prepared transition. No request can supply source bytes, a readiness boolean,
product/config hashes, forced mappings, or an arbitrary restore document.

New required reference assets without genuine fidelity proof block readiness;
their URL metadata is not file/content proof. Existing preserved baseline assets
are bound by their full immutable snapshot, not newly declared validated files.
INDEX_READY alone changes no serving pointer. Source/source-history body assets
and old shared/isolated vectors remain preserved through promotion and rollback.
The transition publishes pointer, controlled visibility and the complete lexical
namespace with exact source/global-corpus CAS in one DB transaction.

Serving holds a bounded shared visibility lease through sync/async completion in
a separate lazy 16-connection pool. Lock order is visibility (shared for corpus
capture, exclusive for activation), source registration, then RAG routing. External
model/Q work is outside activation locks. Full rebuild, RAG preparation/rollback,
title/keyword/context/cache/vector/answer paths retain captured source routing.
Normal index queues exclude managed source groups; all shared law point writes
also require the pre-Q source fence. Legacy runtimes without that fence are not
compatible writers merely because DB triggers reject their later status update.

Runtime index identity includes activated source routing/asset receipts and fails
closed on missing source readiness. Legacy DB/Q count fields still describe their
named shared collections, not a silently summed isolated count. The law integrity
audit keeps source rows in its scanned membership, verifies registered source
namespaces and immutable body/embedding receipts, and rejects missing/drifting
proof and leaked shared points; it does not invent shared embedding rows or suppress
unqualified candidates. A qualified inactive INDEX_READY namespace is audited as
inactive (with its READY receipt), not silently marked serving or APPLIED.
Once sources are active, keep workflow verification wiring available even when
mutations are disabled. Do not manually alter epochs, READY/APPLIED states, hashes,
or preparation receipts. Existing terminal failed evaluation checkpoints remain
non-replayable, and source freshness alone does not prove broader legal scope.

Public DB detail reads hide constructed source candidates until a registered
ACTIVE/RETIRED version has an APPLIED promotion origin and retained Y visibility.
Legacy baseline vector preservation also requires exact INDEXED embedding linkage
for the configured model and shared collection, content hash and raw point ID;
the linkage is checked under the source lock before and after Q readback.
An already constructed official external ID cannot currently be revalidated under
a different product identity: document uniqueness fails closed. Do not force reuse
or relabel its product/manifest; cross-product revalidation is separate work.
