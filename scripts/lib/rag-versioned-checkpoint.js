const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {selectionHash}=require('./rag-eval-provenance');
const {resolveReportPaths,isCheckpointCompatible}=require('./rag-candidate-provenance');
const {buildBlockingGates}=require('./rag-eval-gates');
const {selectVersionedStage}=require('./rag-versioned-evaluation');
function versionedErrorRetryLimit(value=3) {
  const count=Number(value);assert.ok(Number.isSafeInteger(count) && count>=0,'Invalid versioned error retry limit');
  return Math.min(2,count);
}
function applyVersionedSafetyGate(body) {
  const rows=body.results;
  assert.ok(Array.isArray(rows) && rows.every(row=>Number.isSafeInteger(row.unsafeSemanticShadowDisagreementCount)
    && row.unsafeSemanticShadowDisagreementCount>=0),'Missing unsafe semantic shadow counter');
  const unsafe=rows.filter(row=>row.unsafeSemanticShadowDisagreementCount>0);
  const gate={total:rows.length,passed:rows.length-unsafe.length,failed:unsafe.length,
    passRate:rows.length?(rows.length-unsafe.length)/rows.length:0,gatePassed:rows.length>0 && unsafe.length===0,
    blockingFailureIds:unsafe.map(row=>row.id)};
  return {...body,unsafeSemanticShadowDisagreementCount:rows.reduce((sum,row)=>sum+row.unsafeSemanticShadowDisagreementCount,0),
    gatePassed:body.gatePassed===true && rows.length>0 && rows.every(row=>row.passed===true) && gate.gatePassed,
    blockingFailureIds:[...new Set([...(body.blockingFailureIds??[]),...rows.filter(row=>row.passed!==true).map(row=>row.id),...gate.blockingFailureIds])],
    blockingGates:{...(body.blockingGates??buildBlockingGates(rows)),unsafeSemanticShadow:gate}};
}
function assertPriorVersionedCheckpoint({root=process.cwd(),catalogue,stage,identity}={}) {
  if(stage==='penalty')return null;
  const prior={difficult:'penalty',holdout:'difficult',full:'holdout'}[stage];
  assert.ok(prior,'Unknown versioned checkpoint stage');
  const cases=selectVersionedStage(catalogue,prior,{root}),scope=prior==='full'?'full':'targeted';
  const expected={...identity,runScope:scope,selectionHash:selectionHash(cases),selectedCaseCount:cases.length};
  const filename=path.resolve(root,resolveReportPaths(scope,{},expected).outputPath);
  assert.ok(fs.existsSync(filename),'Prior checkpoint PASS is unavailable');
  const stat=fs.lstatSync(filename);assert.ok(stat.isFile() && stat.size>0 && stat.size<=64*1024*1024,'Invalid prior checkpoint artifact');
  const body=JSON.parse(fs.readFileSync(filename,'utf8'));
  assert.ok(isCheckpointCompatible(body,expected) && body.versionedStage===prior
    && body.evaluationContractSha256===identity.evaluationContractSha256,'Prior checkpoint contract/runtime identity differs');
  assert.ok(body.gatePassed===true && body.total===cases.length && body.passed===cases.length && body.failed===0
    && body.minimumPassed===cases.length && Array.isArray(body.blockingFailureIds) && body.blockingFailureIds.length===0
    && body.unsafeSemanticShadowDisagreementCount===0,'Prior checkpoint is not a complete zero-blocking PASS');
  assert.ok(Array.isArray(body.results) && body.results.length===cases.length && body.results.every((row,i)=>row.id===cases[i].id
    && row.question===cases[i].question && row.passed===true && row.unsafeSemanticShadowDisagreementCount===0),'Prior checkpoint result identity differs');
  for(const row of body.results) {
    const kind=catalogue.scoringOverrides[row.id];
    if(kind)assert.ok(row.versionedScoring?.kind===kind && row.versionedScoring.passed===true,'Prior source-qualified gate is missing');
  }
  assert.ok(body.blockingGates && ['curated','answerOracle','noGrounds','unsafeSemanticShadow'].every(key=>body.blockingGates[key]),'Prior blocking gate evidence missing');
  assert.ok(Object.values(body.blockingGates).every(gate=>Number.isSafeInteger(gate.total) && gate.total>=0
    && gate.failed===0 && (gate.total===0 || gate.gatePassed===true)),'Prior blocking gate failed');
  return {stage:prior,path:filename};
}
module.exports={versionedErrorRetryLimit,applyVersionedSafetyGate,assertPriorVersionedCheckpoint};
