# Pandora Runtime Ports

## Ports

- `8080`: app/dev API. Runs `target/pandora-0.0.1-SNAPSHOT.jar`.
- `18080`: batch-runner API. Runs `runtime/batch/pandora-batch-runner.jar`.
- `18081+`: temporary verification ports.

## Rules

1. Source code is shared. Do not create a separate source copy for batch work.
2. Build output in `target/` belongs to app/dev.
3. Batch runner uses a promoted copy of the jar under `runtime/batch/`.
4. Do not restart `18080` from a development chat unless the batch owner asks for it.
5. After a dev build is verified and should become the batch runtime, run:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\promote-batch-runner.ps1
   ```

6. If `18080` is already running, stop it before promotion. Use `-Force` only when preparing the next runtime jar without touching the currently running process.

## Status Check

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\batch-runner-status.ps1
```
