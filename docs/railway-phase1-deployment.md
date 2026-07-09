# Railway Phase 1 Deployment Prep

This phase is for verifying the app with DB and Qdrant only. Original document
files remain out of scope until the object storage phase.

## Generated Export Artifacts

These files are generated under `runtime/`, which is intentionally git-ignored.

### MariaDB Seed

Path:

```text
runtime/exports/railway-seed/20260709-131658
```

Total size: `4,236,297,686` bytes (`3.945 GiB`).

Import in numeric order:

```text
01-schema.sql                         31,935 bytes
02-law-core-data.sql           3,859,144,922 bytes
03-rag-documents.sql              523,428 bytes
04-rag-active-chunks.sql       358,133,501 bytes
05-rag-active-embeddings.sql    18,458,332 bytes
06-rag-collection-sources.sql      5,110 bytes
```

Excluded by default:

- `semantic_batch_jobs`
- `semantic_batch_job_chunks`
- import/sync/search failure logs
- original document assets
- `admin_user`

Because `admin_user` is excluded, set `PANDORA_BOOTSTRAP_ADMIN_USERNAME` and
`PANDORA_BOOTSTRAP_ADMIN_PASSWORD` before the first app start.

### Qdrant Snapshots

Path:

```text
runtime/exports/qdrant-snapshots/20260709-131851
```

Total size: `3,064,471,040` bytes (`2.854 GiB`).

```text
law_chunks-3244258119736370-2026-07-09-04-18-51.snapshot     1,935,645,184 bytes
rag_chunks_v4-3244258119736370-2026-07-09-04-19-01.snapshot  1,128,825,856 bytes
```

Both downloaded files matched the SHA256 checksums returned by the local Qdrant
snapshot API.

## Recommended Railway Services

Use three services for phase 1:

- `pandora-app`: this repository, built by `Dockerfile`
- `mariadb`: MariaDB Docker image with a volume
- `qdrant`: Qdrant Docker image with a volume

MariaDB is recommended over the Railway MySQL template for the first migration
because the local source database is MariaDB 12.2 and the schema contains
MariaDB-oriented SQL. Railway's MySQL template exposes `MYSQLHOST`,
`MYSQLPORT`, `MYSQLUSER`, `MYSQLPASSWORD`, `MYSQLDATABASE`, and `MYSQL_URL`,
but MySQL compatibility should be tested separately before switching.

## App Variables

Use `.env.railway.phase1.example` as the non-secret reference. At minimum:

```text
SPRING_PROFILES_ACTIVE=railway
SPRING_DATASOURCE_URL=jdbc:mariadb://mariadb.railway.internal:3306/pandora?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Seoul
SPRING_DATASOURCE_USERNAME=pandora
SPRING_DATASOURCE_PASSWORD=<secret>
QDRANT_URL=http://qdrant.railway.internal:6333
QDRANT_COLLECTION=law_chunks
QDRANT_RAG_COLLECTION=rag_chunks_v4
OPENAI_API_KEY=<secret>
PANDORA_ADMIN_TOKEN=<secret>
PANDORA_BOOTSTRAP_ADMIN_USERNAME=<secret>
PANDORA_BOOTSTRAP_ADMIN_PASSWORD=<secret>
SPRING_SQL_INIT_MODE=never
LAW_AI_BATCH_SCHEDULER_ENABLED=false
LAW_AI_BATCH_AUTO_ENABLED=false
LAW_AI_BATCH_AUTO_INGEST_ENABLED=false
```

`PORT` is provided by Railway. The app reads it through `server.port=${PORT:8080}`.

## Import Order

1. Create the `mariadb` service and attach a volume.
2. Import the MariaDB seed SQL files in numeric order.
3. Create the `qdrant` service and attach a volume.
4. Restore `law_chunks` and `rag_chunks_v4` from the snapshot files.
5. Create `pandora-app`, set variables, and deploy from GitHub.
6. Verify `/`, login, law search, RAG search, and detail pages.

Do not enable batch scheduler or original document upload/import jobs in phase 1.

## Source Notes

- Railway variables support service variables, reference variables, and `.env`
  suggestions: <https://docs.railway.com/variables>
- Railway MySQL exposes `MYSQLHOST`, `MYSQLPORT`, `MYSQLUSER`,
  `MYSQLPASSWORD`, `MYSQLDATABASE`, and `MYSQL_URL`:
  <https://docs.railway.com/databases/mysql>
- Railway private networking supports service-to-service DNS names like
  `SERVICE_NAME.railway.internal`: <https://docs.railway.com/networking/private-networking>
- Railway currently prices volume storage as resource usage at `$0.15 / GB / month`:
  <https://docs.railway.com/pricing/plans>
