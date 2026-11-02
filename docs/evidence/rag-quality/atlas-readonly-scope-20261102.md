# Atlas read-only writer-scope evidence

User authorized read-only identification, not any Atlas configuration/runtime
change. Automation remainsPAUSED. Approved-host observation e2b5d3:8080PID8620,
6333PID7568,18080PID28296. No stop/restart/mutation was issued to18080 orQ.

## Actual runtime and database

Atlas JAR observed at C:/dev/workspace-egov/atlas/backend/target/atlas-cms-0.1.0.jar,
SHA25643f208545315173fdeba5d88f48ed719f67aff636adf0354a3dbf6ecdb072f78.
The68 packaged application
class/config entries contain no Qdrant/law_chunks/rag_chunks/Pandora-JDBC reference;
no Qdrant library is packaged (7c6fc5). This is static evidence, not an assertion
that arbitrary future code can never make HTTP calls.

Existing normal Atlas configuration specifies127.0.0.1:3306/atlas_cms.
`AtlasReadOnlyScopeDiagnostic` uses that configuration internally without emitting
secret values, sets JDBC read-only and executes metadata SELECTs only.
Actual result346f6e: DATABASE()=atlas_cms, CURRENT_USER()=atlas_app@127.0.0.1,
server port3306. Inherited-role checkdcf63e:CURRENT_ROLE=null, no applicable roles.

Host TCP source ports for PID28296 (2e1c9a) exactly match its10 existing Atlas DB
sessions:1598,8897,8986,31471,33700,38257,41785,50209,51716,62992. All have DB
atlas_cms. Diagnostic session8676 is separate. The account's visible grants are:

- Global USAGE only; no grant option.
- SELECT,UPDATE on atlas_cms.* only.
- INSERT on infra_asset,information_system,ktcloud_volume,ktcloud_nas,
  ktcloud_sync_state,ktcloud_server,ktcloud_sync_history only.
- No table/schema/global grant for Pandora was observed; no inherited role.

Code writes use the single JdbcTemplate datasource and those asset/sync tables
(plus server_asset UPDATE). No cross-schema qualified write, Pandora mapper or
Qdrant client was found. The DB-native grant boundary therefore does not make
Atlas a Pandora DB source writer. Qdrant has no claimed DB-style security fence:
observed source/packaged code lacks a Q writer and actual connections are DB-only;
the absence is bounded to this current runtime/code, not future versions.

Pandora runtime-info names its actual serving collections law_chunks and
rag_chunks_v4; collection enumeration is read-only. Other preserved candidate/
recovery collections were not deleted or written. Do not treat collection
existence as readiness or authorization. This resolves the prior *Atlas-specific*
unknown writer placeholder without changing Atlas; other genuine release gates
remain below.

## Reference/scope diagnosis and limits

Source2100000287088 directly links its annex to Article25 performance evaluation.
Its Article3 governs information-project performance management, not a universal
violation sanction. Article13 preserves the issuing-project/consultation-specific
conditions. This is source relationship evidence, not proof that the penalty
question's unspecified project satisfies them.

Changed native-cell capture accepted all4 declared official artifacts once
(ce6519) with hashes matching the earlier evidence. HWPX annex has8tables; the
other HWPX has0tables. Exploratory substring counts in source-correspondence log
are **not missing-content or fidelity results**: the first diagnostic compared
serialized JSON, which introduces escapes/metadata, while the API text also
interleaves columns and nested tables. Its revised helper reads only annex-body
text and compiles32cb65, but was deliberately not redownloaded/replayed. No
matching count, filename or metadata association grants semantic authority.

Still required: source-owned relational correspondence of annex cells/ranges/
scores to body and all attachment roles, exact immutable proof binding at
transition, and direct penalty applicability. `requireReferences` remains closed.
No live source candidate/update, penalty or ordered later quality gate was run.
