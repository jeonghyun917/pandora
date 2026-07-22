### Task 4: Runtime Promotion and Repeated Evaluation

**Files:**
- Modify: `.superpowers/sdd/progress.md`
- Create: `docs/rag-quality-handoff-20260720-atomic-relations.md`
- Produce logs under `logs/` with unique `atomic-relations` names.

**Interfaces:**
- Consumes: verified backend source and existing Qdrant index.
- Produces: stable-runtime targeted runs, a full 1,004-case run after targeted safety passes, updated score and handoff.

- [ ] **Step 1: Verify runtime state without mutation**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-pandora.ps1
```

Record 8080, 18080, and 6333 state. Do not alter 18080 even if its PID file is stale.

- [ ] **Step 2: Build and validate the staged app-dev JAR**

Run:

```powershell
.\mvnw.cmd -Papp-dev-staged-package -DskipTests package
jar tf .\target-stage\pandora-0.0.1-SNAPSHOT.jar
Get-FileHash -Algorithm SHA256 .\target-stage\pandora-0.0.1-SNAPSHOT.jar
```

Confirm the manifest, `BOOT-INF/classes`, and `BOOT-INF/lib` exist before promotion.

- [ ] **Step 3: Restart only the 8080 app service**

Run the installed app-dev service workflow:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Stop -Role app-dev -Port 8080
Copy-Item -LiteralPath .\target-stage\pandora-0.0.1-SNAPSHOT.jar -Destination .\target\pandora-0.0.1-SNAPSHOT.jar -Force
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-pandora-service.ps1 -Action Start -Role app-dev -Port 8080
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\status-pandora.ps1
```

If the service is no longer installed, use only the documented
`start-pandora.ps1 -Role app-dev -Port 8080 -UseJar` fallback. Never invoke a
batch-runner command.

- [ ] **Step 4: Verify runtime provenance**

Call the 8080 runtime-info endpoint and record:

- instance ID;
- artifact SHA/size;
- configuration SHA;
- Qdrant readiness and search-failure count;
- index revision.

Check the same values before and after each adopted evaluation.

- [ ] **Step 5: Run the 38-case gate twice from zero**

Set:

```powershell
$env:RAG_EVAL_CASE_IDS='project-review-simple-software,project-review-pre-consultation-relation,pre-consultation-target,pre-consultation-when,security-review-target,security-review-exception,it-compliance-penalty,egov-preliminary-review-target,rfp-tech-score-table,public-data-db-standard,procurement-catalog-contract,commercial-sw-direct-purchase,whistleblower-protection-scope,video-cctv-guide,personal-info-purpose,privacy-consent-notice-items,pipc-cctv-public-place-exception,pipc-pseudonym-additional-info,project-review-all-sw-projects,project-review-exclusion-hardware,procurement-digital-service-mall,cctv-public-place-rule,cctv-retention-not-fixed-30,whistleblower-disadvantage,security-review-major-infra,security-review-skip-condition,rfp-requirement-method,commercial-sw-direct-buy-target,procurement-catalog-vs-contract,pseudonym-extra-info-separate,whistleblower-protection-action,privacy-retention-notice,privacy-minimum-collection,privacy-destruction-principle,cctv-install-purpose-limit,public-data-meta-management,admrul-notice-exception,public-data-obligation-system'
$env:RAG_EVAL_RESUME='false'
$env:RAG_EVAL_OUTPUT='logs/rag-eval-gate-targeted-atomic-relations-run1-20260720.json'
$env:RAG_EVAL_REPORT='logs/rag-eval-gate-targeted-atomic-relations-run1-20260720.md'
$env:RAG_EVAL_CHECKPOINT='logs/rag-eval-gate-targeted-atomic-relations-run1-20260720-checkpoint.json'
node .\scripts\rag-eval-gate.js
```

Repeat with `run2` output/report/checkpoint names. The evaluator may exit 1 when
some approved genuine failures remain; that is a quality-gate result, not an
execution failure.

- [ ] **Step 6: Adjudicate repeated targeted results**

For both runs, report:

- passed/failed;
- answer-verification pass count;
- number of `CONTRADICTED` and `CONFLICTED` links;
- which of the 33 observed false-positive IDs remain falsely contradicted;
- whether the five genuine cases remain fail-closed;
- run-to-run flips.

Do not claim the relation fix is stable if the two runs materially disagree.

- [ ] **Step 7: Run the final full gate only after targeted safety passes**

Clear `RAG_EVAL_CASE_IDS`, use unique full-run output/report/checkpoint paths,
confirm runtime provenance, and run:

```powershell
node .\scripts\rag-eval-gate.js
```

Verify exactly 1,004 expected, actual, and unique IDs with no missing,
duplicate, unexpected, or order-mismatched results.

- [ ] **Step 8: Final verification and handoff**

Run fresh:

```powershell
.\mvnw.cmd test
node --test .\scripts\rag-eval-provenance.test.js .\scripts\rag-retrieval-eval.test.js
git diff --check
git status --short --branch
```

Write the handoff with exact artifact paths, hashes, runtime identity, test
counts, targeted/full metrics, remaining failures, and an updated honest
10-point score. Preserve all changes without staging or committing.
