const assert=require('node:assert/strict'),path=require('node:path'),{createHash}=require('node:crypto');
const {readBoundJson,versionedRunnerHash}=require('./rag-versioned-authorization');
const {sourceAuditToolHash}=require('../capture-versioned-source-audit');
const {validateCapturedSourceAudit,assertPackagedCorpusRuntime,assertVersionedExecutionPreflight}=require('./rag-versioned-evaluation');
const sha=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
function prepareApprovalRequest({root=path.resolve(__dirname,'../..'),catalogue,evaluationContractSha256,sourceAuditPath}={}) {
  const audit=readBoundJson(root,sourceAuditPath),runtime=audit.value.runtimeInfo;
  assert.equal(audit.value.auditToolSha256,sourceAuditToolHash(),'Source audit tooling changed');
  validateCapturedSourceAudit({catalogue,evaluationContractSha256,audit:audit.value,before:runtime,after:runtime});
  assertPackagedCorpusRuntime(runtime);
  return {schemaVersion:1,kind:'versioned-evaluation-payload-approval-request',evaluationContractSha256,
    baselineSha256:catalogue.baselineDatasetSha256,questionCount:catalogue.cases.length,
    changedCaseIds:Object.keys(catalogue.scoringOverrides),sourceAuditReference:sourceAuditPath,sourceAuditSha256:audit.sha256,
    runtimeArtifactSha256:runtime.runtimeArtifactSha256,runtimeConfigSha256:runtime.runtimeConfigSha256,
    checkpointPolicy:'candidate-once-v1',evaluationCandidateSha256:runtime.runtimeArtifactSha256,
    runnerSha256:versionedRunnerHash(),requiresExplicitApproval:true};
}
function prepareExecutionManifest({root=path.resolve(__dirname,'../..'),catalogue,evaluationContractSha256,
    approvalRequestPath,approvalReceiptPath,currentRuntimeInfo}={}) {
  const request=readBoundJson(root,approvalRequestPath),receipt=readBoundJson(root,approvalReceiptPath);
  assert.deepEqual(request.value,prepareApprovalRequest({root,catalogue,evaluationContractSha256,
    sourceAuditPath:request.value.sourceAuditReference}),'Approval request no longer matches current inputs');
  const audit=readBoundJson(root,request.value.sourceAuditReference);
  for(const key of ['runtimeArtifactSha256','runtimeConfigSha256','indexVersion','indexRevision','lexicalRevision',
    'embeddingModel','answerModel','lawCollection','ragCollection','lawDatabaseIndexedCount','ragDatabaseIndexedCount',
    'lawDatabaseContentFingerprint','ragDatabaseContentFingerprint'])
    assert.equal(currentRuntimeInfo?.[key],audit.value.runtimeInfo?.[key],'Approved source/corpus payload changed');
  const manifest={schemaVersion:1,evaluationContractSha256,approvalRequestReference:approvalRequestPath,
    approvalRequestSha256:request.sha256,sourceAuditReference:request.value.sourceAuditReference,
    sourceAuditSha256:audit.sha256,sourceOwners:audit.value.sourceOwners,runnerSha256:versionedRunnerHash(),runtimeInfo:currentRuntimeInfo};
  assertVersionedExecutionPreflight({catalogue,evaluationContractSha256,executionManifest:manifest,
    executionManifestSha256:sha(manifest),approvalRequest:request.value,approvalRequestSha256:request.sha256,
    approvalReceipt:receipt.value,currentRuntimeInfo,currentRunnerSha256:versionedRunnerHash()});
  return manifest;
}
module.exports={prepareApprovalRequest,prepareExecutionManifest};
