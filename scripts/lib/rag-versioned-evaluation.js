const fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const assert=require('node:assert/strict');
const {sealProposedCatalogue}=require('./rag-versioned-catalogue');
const {scoreQualifiedConditionalExample}=require('./rag-qualified-example-scorer');
const {scoreReferenceCommencement}=require('./rag-reference-commencement-scorer');
const {isRuntimeStable,assertEvaluationRuntimeReady}=require('./rag-eval-provenance');
const {selectEvalCases}=require('./rag-eval-cases');
const {assertReviewedSplit}=require('../rag-versioned-dataset-preflight');
const canonical=value=>typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
function loadVersionedCatalogue({root=path.resolve(__dirname,'../..'),evaluationContractSha256}={}) {
  if(typeof evaluationContractSha256!=='string' || !/^[a-f0-9]{64}$/.test(evaluationContractSha256))
    throw Error('Expected canonical contract hash');
  const filename=path.join(root,'src/main/resources/rag-evaluation-contracts',evaluationContractSha256+'.json');
  const stat=fs.lstatSync(filename);
  if(!stat.isFile() || stat.size<1 || stat.size>4_000_000)throw Error('Invalid catalogue resource');
  const bytes=fs.readFileSync(filename);
  if(createHash('sha256').update(bytes).digest('hex')!==evaluationContractSha256)throw Error('Catalogue hash mismatch');
  const expected=sealProposedCatalogue({root});
  if(expected.sha256!==evaluationContractSha256 || !expected.bytes.equals(bytes))throw Error('Catalogue inputs or reviewed scorers changed');
  return {catalogue:JSON.parse(bytes),sha256:evaluationContractSha256,liveEligible:false};
}
function scoreVersionedResults(body,catalogue,selectedCases) {
  if(!Array.isArray(selectedCases) || selectedCases.length===0 || body?.total!==selectedCases.length
      || !Array.isArray(body.results) || body.results.length!==selectedCases.length)throw Error('Versioned result count mismatch');
  return body.results.map((row,index)=>{
    const expected=selectedCases[index];
    if(!row || row.id!==expected.id || row.question!==expected.question || typeof row.passed!=='boolean')
      throw Error('Versioned result/request identity mismatch');
    const kind=catalogue.scoringOverrides[row.id];
    if(!kind)return row;
    const proof=row.verificationEnvelope,contract=catalogue.sourceContracts[kind];
    const score=kind==='qualified-approval'?scoreQualifiedConditionalExample
      :kind==='reference-commencement'?scoreReferenceCommencement:null;
    assert.ok(score,'Unknown versioned scorer');
    const accepted=row.answerVerificationRequired===true && row.answerVerified===true
      && proof?.schemaVersion===1 && score(proof,contract)===true;
    const legacyPassed=row.versionedScoring?.kind===kind && typeof row.legacyPassed==='boolean'?row.legacyPassed:row.passed;
    return {...row,legacyPassed,passed:row.passed && legacyPassed && accepted,versionedScoring:{kind,passed:accepted}};
  });
}
function verifyVersionedHttpResponse({response,body,catalogue,requestedCases,evaluationContractSha256}={}) {
  assert.ok(canonical(evaluationContractSha256) && response?.status===200,'Expected versioned HTTP200 response');
  assert.equal(response.headers.get('X-Pandora-Evaluation-Contract-SHA256'),evaluationContractSha256,'Versioned contract attestation mismatch');
  assert.ok(response.headers.get('content-type')?.toLowerCase().startsWith('application/json'),'Expected versioned JSON response');
  return scoreVersionedResults(body,catalogue,requestedCases);
}
function selectVersionedStage(catalogue,stage,{root=path.resolve(__dirname,'../..')}={}) {
  if(stage==='full')return structuredClone(catalogue.cases);
  if(stage==='penalty') {
    const ids=Object.entries(catalogue.scoringOverrides).filter(([,kind])=>kind==='qualified-approval').map(([id])=>id);
    assert.equal(ids.length,1,'One declared penalty case required');
    return structuredClone(selectEvalCases(catalogue.cases,{caseIds:ids}));
  }
  if(!['difficult','holdout'].includes(stage))throw Error('Unknown versioned ladder stage');
  const filename=path.join(root,`src/main/resources/rag-retrieval-${stage}-manifest.json`);
  const split=assertReviewedSplit(stage,fs.readFileSync(filename));
  const cases=selectEvalCases(catalogue.cases,{caseIds:split.trainingCaseIds});
  assert.equal(cases.length,stage==='difficult'?12:57,'Incomplete versioned stage');
  return structuredClone(cases);
}
function assertVersionedExecutionPreflight({catalogue,evaluationContractSha256,executionManifestSha256,
    executionManifest,approvalRequest,approvalRequestSha256,approvalReceipt,currentRuntimeInfo,currentRunnerSha256}={}) {
  assert.ok(canonical(evaluationContractSha256) && canonical(executionManifestSha256),'Canonical execution identity required');
  assert.ok(approvalRequest?.kind==='versioned-evaluation-payload-approval-request'
    && approvalRequest.questionCount===catalogue.cases.length && approvalRequest.questionCount===1003
    && approvalRequest.baselineSha256===catalogue.baselineDatasetSha256
    && approvalRequest.requiresExplicitApproval===true,'Approval payload summary differs from contract');
  assert.deepEqual(approvalRequest.changedCaseIds,Object.keys(catalogue.scoringOverrides),'Approval payload changed-case summary differs');
  assert.ok(canonical(approvalRequestSha256) && approvalRequest?.schemaVersion===1
    && approvalRequest.evaluationContractSha256===evaluationContractSha256
    && approvalReceipt?.schemaVersion===1 && approvalReceipt.approvalRequestSha256===approvalRequestSha256
    && typeof approvalReceipt.authorizationReference==='string' && approvalReceipt.authorizationReference.trim()
    && typeof approvalReceipt.approvedAt==='string' && Number.isFinite(Date.parse(approvalReceipt.approvedAt)),
    'Exact explicit payload approval receipt required');
  assert.ok(executionManifest?.schemaVersion===1 && executionManifest.evaluationContractSha256===evaluationContractSha256
    && executionManifest.approvalRequestSha256===approvalRequestSha256
    && executionManifest.sourceAuditSha256===approvalRequest.sourceAuditSha256
    && canonical(executionManifest.sourceAuditSha256) && typeof executionManifest.sourceAuditReference==='string'
    && executionManifest.sourceAuditReference.trim(),'Bound source audit required');
  const expected=executionManifest.runtimeInfo;
  if(Object.hasOwn(approvalRequest,'checkpointPolicy') || Object.hasOwn(approvalRequest,'evaluationCandidateSha256'))
    assert.ok(approvalRequest.checkpointPolicy==='candidate-once-v1' && canonical(approvalRequest.evaluationCandidateSha256)
      && approvalRequest.evaluationCandidateSha256===approvalRequest.runtimeArtifactSha256,
      'Approved candidate/policy must match the immutable JAR');
  assert.ok(canonical(currentRunnerSha256) && currentRunnerSha256===approvalRequest.runnerSha256
    && currentRunnerSha256===executionManifest.runnerSha256,'Approved runner implementation changed');
  assert.ok(expected?.runtimeArtifactSha256===approvalRequest.runtimeArtifactSha256
    && expected?.runtimeConfigSha256===approvalRequest.runtimeConfigSha256,'Approved artifact/configuration changed');
  assert.ok(isRuntimeStable(expected,currentRuntimeInfo),'Approved runtime/corpus identity changed');
  assertPackagedCorpusRuntime(currentRuntimeInfo);
  assertSourceOwners(catalogue,executionManifest.sourceOwners);
}
function assertPackagedCorpusRuntime(currentRuntimeInfo) {
  assertEvaluationRuntimeReady(currentRuntimeInfo);
  assert.equal(currentRuntimeInfo.qdrantSearchFailureCount,0,'Search failures must be zero');
  for(const key of ['runtimeArtifactSha256','runtimeConfigSha256','indexRevision','lexicalRevision'])
    assert.ok(canonical(currentRuntimeInfo[key]),'Missing exact runtime fingerprint');
  for(const key of ['lawDatabaseContentFingerprint','ragDatabaseContentFingerprint'])
    assert.ok(typeof currentRuntimeInfo[key]==='string' && /^[a-fA-F0-9]{64}$/.test(currentRuntimeInfo[key]),'Missing corpus fingerprint');
  assert.ok(currentRuntimeInfo.runtimeArtifactKind==='jar' && Number.isSafeInteger(currentRuntimeInfo.runtimeArtifactSize)
    && currentRuntimeInfo.runtimeArtifactSize>=10_000_000,'Official packaged runtime required');
  for(const prefix of ['law','rag']) {
    const db=currentRuntimeInfo[prefix+'DatabaseIndexedCount'],q=currentRuntimeInfo[prefix+'QdrantExactPointCount'];
    assert.ok(Number.isSafeInteger(db) && db>0 && db===q,'DB/Qdrant counts must match');
  }
}
function assertSourceOwners(catalogue,sourceOwners) {
  const a=catalogue.sourceContracts['qualified-approval'],r=catalogue.sourceContracts['reference-commencement'];
  const required=[{...a.qualification.owner,edition:a.indexedEdition},{...a.remedy.owner,edition:a.indexedEdition},
    {...r.owner,edition:r.edition,chunkVersion:r.chunkVersion}];
  assert.ok(Array.isArray(sourceOwners) && sourceOwners.length===required.length,'Complete source-owner snapshot required');
  for(const owner of required) {
    const matches=sourceOwners.filter(row=>row && row.target===owner.target
      && row.documentId===owner.documentId && row.chunkId===owner.chunkId);
    assert.equal(matches.length,1,'Unique source owner required');
    const actual=matches[0];
    assert.ok(actual.edition===owner.edition && Number.isSafeInteger(actual.chunkVersion) && actual.chunkVersion>0
      && (owner.chunkVersion===undefined || actual.chunkVersion===owner.chunkVersion)
      && canonical(actual.chunkTextSha256) && actual.effectiveStatus==='CURRENT'
      && actual.activationStatus==='ACTIVE' && actual.useYn==='Y' && actual.qualityStatus==='PASS','Source snapshot differs from reviewed owner');
  }
}
function validateCapturedSourceAudit({catalogue,evaluationContractSha256,audit,before,after}={}) {
  assertEvaluationRuntimeReady(before);assertEvaluationRuntimeReady(after);
  assert.ok(isRuntimeStable(before,after) && before.qdrantSearchFailureCount===0,'Runtime changed during read-only source audit');
  assert.ok(audit?.schemaVersion===1 && audit.evaluationContractSha256===evaluationContractSha256
    && audit.auditMode==='read-only-db-consistent-snapshot','Unexpected source audit result');
  for(const key of ['lawDatabaseIndexedCount','ragDatabaseIndexedCount','lawDatabaseContentFingerprint','ragDatabaseContentFingerprint'])
    assert.equal(audit[key],before[key],'Source audit datasource/corpus differs from server');
  assertSourceOwners(catalogue,audit.sourceOwners);
}
module.exports={loadVersionedCatalogue,scoreVersionedResults,selectVersionedStage,assertVersionedExecutionPreflight,validateCapturedSourceAudit,verifyVersionedHttpResponse,assertPackagedCorpusRuntime};
