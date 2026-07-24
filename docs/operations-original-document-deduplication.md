# Original document deduplication

This maintenance command removes byte-identical repeated ministry attachment
downloads while retaining one canonical original per article directory.

It never treats file size or filename as proof of identity. SHA-256 must match.
Duplicates across different article directories are reported but not removed.

## Environment

The command uses:

- `MARIADB_EXE`
- `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`
- or `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`

Local defaults match `application.properties`. Passwords are passed through
`MYSQL_PWD` and are not printed.

## Plan

```powershell
node scripts/ministry-original-dedup.js plan `
  --root C:\dev\workspace-egov\pandora\data\rag-upload\ministry_docs `
  --output C:\dev\workspace-egov\pandora\logs\storage-dedup\20260724
```

Review both generated files:

- `ministry-original-dedup-plan.json`
- `ministry-original-dedup-plan.csv`

## Apply

Apply refuses to run when a collection row remains in `RUNNING` status. It also
rehashes every planned file and verifies the manifest digest before changing the
database.

```powershell
node scripts/ministry-original-dedup.js apply `
  --root C:\dev\workspace-egov\pandora\data\rag-upload\ministry_docs `
  --manifest C:\dev\workspace-egov\pandora\logs\storage-dedup\20260724\ministry-original-dedup-plan.json
```

Database paths are consolidated before files are permanently deleted. The
command writes `ministry-original-dedup-apply-result.json` beside the plan.

The manifest is an audit record, not a byte-level backup.

## Collector behavior

Normal ministry collection runs use `refreshExisting=false`. An attachment is
reused without another download only when its stored path is still inside the
article directory and its current SHA-256 matches the database value.

Use `refreshExisting=true` when an operator needs to check whether an upstream
server replaced bytes at the same attachment URL. Changed bytes are retained
once with a stable SHA-256 suffix; identical bytes reuse the canonical file.
