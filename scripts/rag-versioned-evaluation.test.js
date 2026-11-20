const test=require('node:test'),assert=require('node:assert/strict');
const {loadVersionedCatalogue,scoreVersionedResults,assertVersionedExecutionPreflight,selectVersionedStage,validateCapturedSourceAudit,verifyVersionedHttpResponse}=require('./lib/rag-versioned-evaluation');
const HASH='8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43';
const link=(claim,number)=>({claim,relation:'SUPPORTED',groundNumber:number,evidenceSentence:claim,overlapCount:5,coverage:1,score:1});
function envelope(catalogue,id) {
  const kind=catalogue.scoringOverrides[id],contract=catalogue.sourceContracts[kind];
  let claims,owners,phase;
  if(kind==='qualified-approval') {
    claims=[contract.qualification.literal,Object.values(contract.remedy.anchors).join(' ')];
    owners=[contract.qualification.owner,contract.remedy.owner];
  } else {
    claims=[`${contract.title} 시행일은 2026년 8월 28일이다.`,`${contract.title}의 일반 시행 상태는 2026년 11월 18일 기준 시행 중이다.`];
    owners=[contract.owner,contract.owner];
    phase={lawId:contract.lawId,edition:contract.edition,documentId:contract.owner.documentId,chunkId:contract.owner.chunkId,
      chunkVersion:contract.chunkVersion,effectiveFrom:contract.effectiveFrom,effectiveUntilExclusive:'2027-02-28',referenceDate:contract.asOf,
      observedAt:'2026-11-20',sourceSha256:'a'.repeat(64),commencement:{effectiveDate:contract.effectiveFrom,
        sourcePath:'$.법령.부칙.부칙단위[2]',clause:'제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다.'}};
  }
  const links=claims.map((claim,i)=>link(claim,kind==='qualified-approval'?i+1:1));
  return {schemaVersion:1,verifiedAnswer:claims.join(' '),aligned:true,insufficientEvidence:false,
    unsupportedAnswerClaims:[],unsupportedNumericClaims:[],contradictedAnswerClaims:[],unsafeSemanticShadowDisagreementCount:0,
    claimEvidenceLinks:links,attributedClaims:links.map((l,i)=>({link:l,...owners[i]})),referencePhase:phase};
}
function result(catalogue,row) {return {id:row.id,question:row.question,passed:true,answerVerificationRequired:true,answerVerified:true,
  verificationEnvelope:envelope(catalogue,row.id)};}
test('loads exact sealed request bytes and cannot infer registration or approval',()=>{
  const loaded=loadVersionedCatalogue({evaluationContractSha256:HASH});
  assert.equal(loaded.sha256,HASH);assert.equal(loaded.catalogue.cases.length,1003);
  assert.equal(loaded.liveEligible,false);
  assert.throws(()=>loadVersionedCatalogue({evaluationContractSha256:'../escape'}),/canonical|hash/i);
});
test('adds strict full-envelope scoring for both declared overrides without flipping a legacy failure',()=>{
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const selected=catalogue.cases.filter(row=>Object.hasOwn(catalogue.scoringOverrides,row.id));
  const rows=selected.map(row=>result(catalogue,row));
  assert.equal(scoreVersionedResults({total:rows.length,results:rows},catalogue,selected).every(row=>row.passed),true);
  for(const change of [r=>{r.passed=false;},r=>{r.verificationEnvelope=null;},r=>{r.verificationEnvelope.schemaVersion=0;},
    r=>{r.verificationEnvelope.aligned=false;},r=>{r.verificationEnvelope.unsupportedNumericClaims=['unsupported'];},r=>{r.answerVerified=false;}]) {
    const bad=structuredClone(rows);change(bad[0]);
    assert.equal(scoreVersionedResults({total:bad.length,results:bad},catalogue,selected)[0].passed,false);
  }
});
test('rejects missing, extra, duplicate or cross-request result identities',()=>{
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const selected=catalogue.cases.filter(row=>Object.hasOwn(catalogue.scoringOverrides,row.id));
  const rows=selected.map(row=>result(catalogue,row));
  for(const bad of [rows.slice(1),[rows[0],rows[0]],[...rows,rows[0]],rows.toReversed(),
    rows.map((row,i)=>i?row:{...row,question:'Other question'})])
    assert.throws(()=>scoreVersionedResults({total:bad.length,results:bad},catalogue,selected),/identity|result|request|count/i);
});
test('batch and final proof checks preserve the original legacy verdict for diagnostics',()=>{
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const selected=catalogue.cases.filter(row=>row.id==='it-compliance-penalty');
  const row=result(catalogue,selected[0]);row.verificationEnvelope=null;
  const first=scoreVersionedResults({total:1,results:[row]},catalogue,selected);
  assert.equal(first[0].legacyPassed,true);assert.equal(first[0].passed,false);
  const final=scoreVersionedResults({total:1,results:first},catalogue,selected);
  assert.equal(final[0].legacyPassed,true);assert.equal(final[0].passed,false);
});
test('HTTP contract attestation and JSON are required before trusting response proof',()=>{
  assert.equal(typeof verifyVersionedHttpResponse,'function');
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const selected=catalogue.cases.filter(row=>row.id==='it-compliance-penalty');
  const body={total:1,results:[result(catalogue,selected[0])]};
  const response=(status=200,hash=HASH,type='application/json')=>new Response('{}',{status,headers:{'content-type':type,'X-Pandora-Evaluation-Contract-SHA256':hash}});
  const input={response:response(),body,catalogue,requestedCases:selected,evaluationContractSha256:HASH};
  assert.equal(verifyVersionedHttpResponse(input)[0].passed,true);
  for(const bad of [response(409),response(200,'a'.repeat(64)),response(200,HASH,'text/plain')])
    assert.throws(()=>verifyVersionedHttpResponse({...input,response:bad}),/HTTP|contract|JSON|attest/i);
});
test('selects only declared ladder stages and preserves original subset order',()=>{
  assert.equal(typeof selectVersionedStage,'function');
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  for(const [stage,size] of Object.entries({penalty:1,difficult:12,holdout:57,full:1003})) {
    const selected=selectVersionedStage(catalogue,stage);
    assert.equal(selected.length,size);
    const ids=new Set(selected.map(row=>row.id));
    assert.deepEqual(selected.map(row=>row.id),catalogue.cases.filter(row=>ids.has(row.id)).map(row=>row.id));
  }
  assert.throws(()=>selectVersionedStage(catalogue,'unknown'),/stage/i);
});
test('requires exact payload approval and source/runtime binding before any slot or request',()=>{
  assert.equal(typeof assertVersionedExecutionPreflight,'function');
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const runtime={source:'server',runtimeArtifactSha256:'a'.repeat(64),runtimeInstanceId:'instance',runtimeConfigSha256:'b'.repeat(64),
    runtimeArtifactKind:'jar',runtimeArtifactSize:69_000_000,runtimeArtifactPath:'C:/fixture/pandora.jar',runtimeArtifactModifiedAt:'2026-11-20T00:00:00Z',
    indexVersion:'law_chunks+rag_chunks_v4',embeddingModel:'text-embedding-3-small',answerModel:'gpt-5-mini',lawCollection:'law_chunks',ragCollection:'rag_chunks_v4',
    indexRevision:'c'.repeat(64),lexicalRevision:'d'.repeat(64),qdrantReady:true,qdrantSearchFailureCount:0,
    lawDatabaseIndexedCount:10,lawQdrantExactPointCount:10,ragDatabaseIndexedCount:20,ragQdrantExactPointCount:20,
    lawDatabaseContentFingerprint:'e'.repeat(64),ragDatabaseContentFingerprint:'f'.repeat(64)};
  const approvalRequest={schemaVersion:1,kind:'versioned-evaluation-payload-approval-request',questionCount:1003,
    baselineSha256:catalogue.baselineDatasetSha256,changedCaseIds:Object.keys(catalogue.scoringOverrides),requiresExplicitApproval:true,
    evaluationContractSha256:HASH,sourceAuditSha256:'3'.repeat(64),
    runtimeArtifactSha256:runtime.runtimeArtifactSha256,runtimeConfigSha256:runtime.runtimeConfigSha256,runnerSha256:'4'.repeat(64)};
  const approval={schemaVersion:1,approvalRequestSha256:'5'.repeat(64),
    authorizationReference:'synthetic local test only',approvedAt:'2026-11-20T00:00:00Z'};
  const a=catalogue.sourceContracts['qualified-approval'],r=catalogue.sourceContracts['reference-commencement'];
  const owners=[{...a.qualification.owner,edition:a.indexedEdition},{...a.remedy.owner,edition:a.indexedEdition},
    {...r.owner,edition:r.edition}].map(owner=>({...owner,chunkVersion:1,chunkTextSha256:'2'.repeat(64),
      effectiveStatus:'CURRENT',activationStatus:'ACTIVE',useYn:'Y',qualityStatus:'PASS'}));
  const manifest={schemaVersion:1,evaluationContractSha256:HASH,approvalRequestSha256:'5'.repeat(64),runnerSha256:'4'.repeat(64),runtimeInfo:structuredClone(runtime),sourceOwners:owners,
    sourceAuditReference:'synthetic read-only fixture',sourceAuditSha256:'3'.repeat(64)};
  const inputs={catalogue,evaluationContractSha256:HASH,executionManifestSha256:'1'.repeat(64),executionManifest:manifest,
    approvalRequest,approvalRequestSha256:'5'.repeat(64),approvalReceipt:approval,currentRuntimeInfo:runtime,currentRunnerSha256:'4'.repeat(64)};
  assert.doesNotThrow(()=>assertVersionedExecutionPreflight(inputs));
  for(const mutate of [x=>{x.approvalReceipt=null;},x=>{x.approvalRequest.evaluationContractSha256='6'.repeat(64);},
    x=>{x.approvalReceipt.approvalRequestSha256='6'.repeat(64);},x=>{x.currentRuntimeInfo.runtimeArtifactSha256='6'.repeat(64);},
    x=>{x.approvalRequest.sourceAuditSha256='6'.repeat(64);},x=>{x.currentRunnerSha256='6'.repeat(64);},
    x=>{x.currentRuntimeInfo.qdrantReady=false;},x=>{x.currentRuntimeInfo.qdrantSearchFailureCount=1;},
    x=>{x.executionManifest.sourceOwners[0].chunkId=1;},x=>{x.executionManifest.sourceOwners[0].chunkTextSha256='';},
    x=>{x.executionManifest.sourceOwners[2].chunkVersion=2;},x=>{x.executionManifest.sourceOwners[0].effectiveStatus='HISTORICAL';},
    x=>{x.executionManifest.sourceOwners[0].qualityStatus='MACRO_PARTIAL';}]) {
    const bad=structuredClone(inputs);mutate(bad);assert.throws(()=>assertVersionedExecutionPreflight(bad));
  }
});
test('read-only audit must match the server corpus and every reviewed source owner',()=>{
  assert.equal(typeof validateCapturedSourceAudit,'function');
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const before={source:'server',runtimeArtifactSha256:'a'.repeat(64),runtimeInstanceId:'fixture',runtimeConfigSha256:'b'.repeat(64),
    qdrantReady:true,qdrantSearchFailureCount:0,lawDatabaseIndexedCount:10,ragDatabaseIndexedCount:20,
    lawDatabaseContentFingerprint:'c'.repeat(64),ragDatabaseContentFingerprint:'d'.repeat(64)};
  const a=catalogue.sourceContracts['qualified-approval'],r=catalogue.sourceContracts['reference-commencement'];
  const owners=[{...a.qualification.owner,edition:a.indexedEdition},{...a.remedy.owner,edition:a.indexedEdition},
    {...r.owner,edition:r.edition}].map(owner=>({...owner,chunkVersion:1,chunkTextSha256:'e'.repeat(64),
      effectiveStatus:'CURRENT',activationStatus:'ACTIVE',useYn:'Y',qualityStatus:'PASS'}));
  const audit={schemaVersion:1,evaluationContractSha256:HASH,auditMode:'read-only-db-consistent-snapshot',sourceOwners:owners,
    lawDatabaseIndexedCount:10,ragDatabaseIndexedCount:20,lawDatabaseContentFingerprint:'c'.repeat(64),ragDatabaseContentFingerprint:'d'.repeat(64)};
  const inputs={catalogue,evaluationContractSha256:HASH,audit,before,after:structuredClone(before)};
  assert.doesNotThrow(()=>validateCapturedSourceAudit(inputs));
  for(const change of [x=>{x.audit.lawDatabaseContentFingerprint='f'.repeat(64);},x=>{x.after.runtimeInstanceId='changed';},
    x=>{x.audit.sourceOwners[0].edition='other';},x=>{x.audit.sourceOwners[0].qualityStatus='MACRO_PARTIAL';}]) {
    const bad=structuredClone(inputs);change(bad);assert.throws(()=>validateCapturedSourceAudit(bad));
  }
});
