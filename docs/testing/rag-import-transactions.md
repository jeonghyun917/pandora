# RAG import transaction verification

`RagImportTransactionTests` uses a real MariaDB schema and the production MyBatis mapper. OpenAI, Qdrant network calls, and lexical rebuild are controlled external boundaries. It checks lexical failure, embedding failure, partial Qdrant write, final document-update failure after Qdrant writes, and successful commit.

## Isolation requirements

- Use a fresh, dedicated MariaDB instance bound to loopback port23306, never the shared Pandora database.
- The database must be named `pandora_import_tx_test`.
- Set `PANDORA_IMPORT_TX_TEST_DATADIR` to its real data directory. Its final directory name must start with `pandora-import-tx-test-`.
- Before fixture DDL or deletion, the test verifies the server port, database name, and canonical data-directory path.
- The fixture loads `schema.sql` and deletes its test rows. Do not place other data in this schema.
- Do not register a Windows service or change existing database/app/Qdrant services to run this test. The test itself does not start or stop servers.

## Run

Provide the isolated instance's password through `PANDORA_IMPORT_TX_TEST_PASSWORD`, then set the two non-secret parameters:

```powershell
$env:PANDORA_IMPORT_TX_TEST_URL = 'jdbc:mariadb://127.0.0.1:23306/pandora_import_tx_test?sslMode=disable'
$env:PANDORA_IMPORT_TX_TEST_DATADIR = 'C:\path\to\pandora-import-tx-test-run'
.\mvnw.cmd '-Dtest=RagImportTransactionTests,RagImportPreflightTests' test
```

The transaction test is skipped when the explicit isolated URL is absent. To include it in the full suite, keep these variables set and run `.\mvnw.cmd test`. Never substitute production credentials or a production URL.

## Guarantee boundaries

Each validated import draft is persisted in its own transaction, including calls made by the public batch entry points. Confirmed rollback cleans only the attempted new chunk IDs; old point deletion remains after commit. Failed, skipped, or deferred-index preferred files do not deactivate alternative documents.

The test does not prove real Qdrant network cleanup succeeds, resolve an unknown commit outcome, or authorize corpus replacement. Those cases still require reconciliation and a fresh DB/Q identity/health fence. Source-condition preservation and answer-quality release gates remain separate requirements.
