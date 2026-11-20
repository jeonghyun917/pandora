const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {assertPriorVersionedCheckpoint,applyVersionedSafetyGate,versionedErrorRetryLimit}=require('./lib/rag-versioned-checkpoint');
const {fixture,HASH}=require('./test-support/versioned-evaluation-fixture');
const {selectVersionedStage}=require('./lib/rag-versioned-evaluation');
const {buildCheckpointIdentity,selectionHash,resolveReportPaths}=require('./lib/rag-eval-provenance');
const {buildBlockingGates}=require('./lib/rag-eval-gates');
test('unsafe disagreement remains release blocking even when legacy case matching passed',()=>{
  const row={id:'case',passed:true,answerVerificationRequired:true,unsafeSemanticShadowDisagreementCount:1};
  const body=applyVersionedSafetyGate({results:[row],gatePassed:true,blockingFailureIds:[],blockingGates:buildBlockingGates([row])});
  assert.equal(body.results[0].passed,true);assert.equal(body.gatePassed,false);
  assert.deepEqual(body.blockingFailureIds,['case']);assert.equal(body.blockingGates.unsafeSemanticShadow.failed,1);
  assert.throws(()=>applyVersionedSafetyGate({results:[{id:'unknown',passed:true}],gatePassed:true}),/unsafe|counter|shadow/i);
});
test('versioned error handling cannot exceed three total question attempts',()=>{
  assert.equal(versionedErrorRetryLimit(),2);assert.equal(versionedErrorRetryLimit(3),2);assert.equal(versionedErrorRetryLimit(100),2);
  assert.equal(versionedErrorRetryLimit(0),0);assert.equal(versionedErrorRetryLimit('1'),1);
  for(const value of [-1,'bad',Infinity,1.5])assert.throws(()=>versionedErrorRetryLimit(value));
});
test('only a complete same-contract/runtime prior PASS can open the next stage',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-prior-'));
  try {
    const f=fixture(),cases=selectVersionedStage(f.catalogue,'penalty');
    const currentCases=selectVersionedStage(f.catalogue,'difficult');
    const identity=buildCheckpointIdentity({scope:'targeted',baseUrl:'http://localhost:8080',datasetHashValue:HASH,
      selectionHashValue:selectionHash(currentCases),selectedCount:currentCases.length,runtimeInfo:f.runtime,
      baselineManifestId:f.authorization.identitySha256,evaluationContractSha256:HASH});
    const previous={...identity,selectionHash:selectionHash(cases),selectedCaseCount:cases.length};
    const target=path.resolve(root,resolveReportPaths('targeted',{},previous).outputPath);fs.mkdirSync(path.dirname(target),{recursive:true});
    const row={...f.result,unsafeSemanticShadowDisagreementCount:0,versionedScoring:{kind:'qualified-approval',passed:true}};
    const body={checkpointIdentity:previous,versionedStage:'penalty',evaluationContractSha256:HASH,gatePassed:true,total:1,passed:1,failed:0,minimumPassed:1,blockingFailureIds:[],
      unsafeSemanticShadowDisagreementCount:0,results:[row],blockingGates:{...buildBlockingGates([row]),unsafeSemanticShadow:{total:1,failed:0,gatePassed:true}}};
    const input={root,catalogue:f.catalogue,stage:'difficult',identity};
    assert.throws(()=>assertPriorVersionedCheckpoint(input),/prior|checkpoint/i);
    const write=value=>fs.writeFileSync(target,JSON.stringify(value));write(body);
    assert.doesNotThrow(()=>assertPriorVersionedCheckpoint(input));
    for(const change of [b=>{b.gatePassed=false;},b=>{b.checkpointIdentity.runtimeInstanceId='other';},
      b=>{b.results[0].versionedScoring.passed=false;},b=>{b.unsafeSemanticShadowDisagreementCount=1;},
      b=>{b.results[0].question='Other request';}]) {
      const bad=structuredClone(body);change(bad);write(bad);assert.throws(()=>assertPriorVersionedCheckpoint(input));
    }
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-prior-'));fs.rmSync(root,{recursive:true,force:true});}
});
