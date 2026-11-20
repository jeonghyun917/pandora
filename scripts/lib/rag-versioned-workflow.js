const assert=require('node:assert/strict');
const {selectVersionedStage,assertVersionedExecutionPreflight,scoreVersionedResults}=require('./rag-versioned-evaluation');
const {selectionHash}=require('./rag-eval-provenance');
const {buildCheckpointIdentity}=require('./rag-candidate-provenance');
function assertApprovedAudit(actual,approved) {
  assert.deepEqual(actual.sourceOwners,approved.sourceOwners,'Approved source content/version changed');
  for(const key of ['lawDatabaseIndexedCount','ragDatabaseIndexedCount','lawDatabaseContentFingerprint','ragDatabaseContentFingerprint',
    'auditToolSha256','probeClasspathSha256']) assert.equal(actual[key],approved[key],'Approved source audit identity changed');
}
async function runVersionedWorkflow(options) {
  const {catalogue,evaluationContractSha256,stage,baseUrl,authorization,root,
    preflightContract,captureSourceAudit,assertPredecessor,reserveSlot,evaluate,recomputeGate,assertInputsStable,persist,recordFailure}=options;
  for(const callback of [preflightContract,captureSourceAudit,assertPredecessor,reserveSlot,evaluate,recomputeGate,assertInputsStable,persist,recordFailure])
    assert.equal(typeof callback,'function','Complete versioned workflow callbacks required');
  const cases=selectVersionedStage(catalogue,stage,{root}),scope=stage==='full'?'full':'targeted';
  const preflight=currentRuntimeInfo=>assertVersionedExecutionPreflight({catalogue,evaluationContractSha256,
    ...authorization,currentRuntimeInfo});
  // Receipt/artifact sanity must fail before any GET, DB read, slot or model call.
  preflight(authorization.executionManifest.runtimeInfo);
  await preflightContract();
  const before=await captureSourceAudit();assertApprovedAudit(before,authorization.sourceAudit);preflight(before.runtimeInfo);
  const identity=buildCheckpointIdentity({scope,baseUrl,datasetHashValue:evaluationContractSha256,
    selectionHashValue:selectionHash(cases),selectedCount:cases.length,gateProfile:'release',runtimeInfo:before.runtimeInfo,
    baselineManifestId:authorization.identitySha256,evaluationContractSha256,
    ...(authorization.approvalRequest.checkpointPolicy==='candidate-once-v1'
      ? {evaluationCandidateSha256:authorization.approvalRequest.evaluationCandidateSha256}:{} )});
  const context={cases,scope,stage,identity,runtimeInfo:before.runtimeInfo,sourceAuditBefore:before};
  if(stage!=='penalty')await assertPredecessor(context);
  let reserved=false;
  try {
    context.paths=await reserveSlot(context);reserved=true;
    const evaluated=await evaluate(context);
    const scored=recomputeGate({...evaluated,results:scoreVersionedResults(evaluated,catalogue,cases)});
    const after=await captureSourceAudit();assertApprovedAudit(after,authorization.sourceAudit);preflight(after.runtimeInfo);
    await assertInputsStable();
    const body={...scored,checkpointIdentity:identity,versionedStage:stage,evaluationContractSha256,
      versionedEvidence:{executionManifestSha256:authorization.executionManifestSha256,
        approvalRequestSha256:authorization.approvalRequestSha256,approvalReceiptSha256:authorization.approvalReceiptSha256,
        sourceAuditBefore:before,sourceAuditAfter:after}};
    await persist(body,context);return body;
  } catch(error) {
    if(reserved)await recordFailure(error,context);
    throw error;
  }
}
module.exports={runVersionedWorkflow};
