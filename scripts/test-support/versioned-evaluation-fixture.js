const {loadVersionedCatalogue}=require('../lib/rag-versioned-evaluation');
const HASH='8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43';
function fixture() {
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const runtime={source:'server',runtimeArtifactSha256:'a'.repeat(64),runtimeInstanceId:'instance',runtimeConfigSha256:'b'.repeat(64),
    runtimeArtifactKind:'jar',runtimeArtifactSize:69_000_000,indexVersion:'law+rag',embeddingModel:'embedding',answerModel:'answer',lawCollection:'law',ragCollection:'rag',
    indexRevision:'c'.repeat(64),lexicalRevision:'d'.repeat(64),qdrantReady:true,qdrantSearchFailureCount:0,
    lawDatabaseIndexedCount:10,lawQdrantExactPointCount:10,ragDatabaseIndexedCount:20,ragQdrantExactPointCount:20,
    lawDatabaseContentFingerprint:'e'.repeat(64),ragDatabaseContentFingerprint:'f'.repeat(64)};
  const a=catalogue.sourceContracts['qualified-approval'],r=catalogue.sourceContracts['reference-commencement'];
  const sourceOwners=[{...a.qualification.owner,edition:a.indexedEdition},{...a.remedy.owner,edition:a.indexedEdition},{...r.owner,edition:r.edition}]
    .map(owner=>({...owner,chunkVersion:1,chunkTextSha256:'1'.repeat(64),effectiveStatus:'CURRENT',activationStatus:'ACTIVE',useYn:'Y',qualityStatus:'PASS'}));
  const sourceAudit={schemaVersion:1,evaluationContractSha256:HASH,auditMode:'read-only-db-consistent-snapshot',sourceOwners,
    auditToolSha256:'2'.repeat(64),probeClasspathSha256:'3'.repeat(64),runtimeInfo:runtime,
    lawDatabaseIndexedCount:10,ragDatabaseIndexedCount:20,lawDatabaseContentFingerprint:runtime.lawDatabaseContentFingerprint,ragDatabaseContentFingerprint:runtime.ragDatabaseContentFingerprint};
  const approvalRequest={schemaVersion:1,kind:'versioned-evaluation-payload-approval-request',questionCount:1003,
    baselineSha256:catalogue.baselineDatasetSha256,changedCaseIds:Object.keys(catalogue.scoringOverrides),requiresExplicitApproval:true,
    evaluationContractSha256:HASH,sourceAuditSha256:'4'.repeat(64),
    runtimeArtifactSha256:runtime.runtimeArtifactSha256,runtimeConfigSha256:runtime.runtimeConfigSha256,runnerSha256:'5'.repeat(64)};
  const executionManifest={schemaVersion:1,evaluationContractSha256:HASH,approvalRequestSha256:'6'.repeat(64),runnerSha256:'5'.repeat(64),runtimeInfo:structuredClone(runtime),
    sourceOwners:structuredClone(sourceOwners),sourceAuditSha256:'4'.repeat(64),sourceAuditReference:'synthetic local fixture'};
  const authorization={executionManifest,executionManifestSha256:'7'.repeat(64),approvalRequest,approvalRequestSha256:'6'.repeat(64),
    approvalReceipt:{schemaVersion:1,approvalRequestSha256:'6'.repeat(64),authorizationReference:'synthetic local test only',approvedAt:'2026-11-20T00:00:00Z'},
    approvalReceiptSha256:'8'.repeat(64),sourceAudit,currentRunnerSha256:'5'.repeat(64),identitySha256:'9'.repeat(64)};
  const claims=[a.qualification.literal,Object.values(a.remedy.anchors).join(' ')];
  const links=claims.map((claim,i)=>({claim,relation:'SUPPORTED',groundNumber:i+1,evidenceSentence:claim,overlapCount:5,coverage:1,score:1}));
  const row=catalogue.cases.find(row=>row.id==='it-compliance-penalty');
  const result={id:row.id,question:row.question,passed:true,answerVerified:true,answerVerificationRequired:true,
    verificationEnvelope:{schemaVersion:1,verifiedAnswer:claims.join(' '),aligned:true,insufficientEvidence:false,
      unsupportedAnswerClaims:[],unsupportedNumericClaims:[],contradictedAnswerClaims:[],unsafeSemanticShadowDisagreementCount:0,
      claimEvidenceLinks:links,attributedClaims:links.map((link,i)=>({link,...(i?a.remedy.owner:a.qualification.owner)}))}};
  return {catalogue,runtime,sourceAudit,authorization,result};
}
module.exports={fixture,HASH};
