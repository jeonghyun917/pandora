const test=require('node:test'),assert=require('node:assert/strict');
const {runVersionedWorkflow}=require('./lib/rag-versioned-workflow');
const {fixture,HASH}=require('./test-support/versioned-evaluation-fixture');
function options(f,trace) {
  return {catalogue:f.catalogue,evaluationContractSha256:HASH,stage:'penalty',baseUrl:'http://localhost:8080',authorization:f.authorization,
    preflightContract:async()=>{trace.push('capability');},captureSourceAudit:async()=>{trace.push('audit');return structuredClone(f.sourceAudit);},
    assertPredecessor:async()=>{trace.push('predecessor');},reserveSlot:()=>{trace.push('slot');return {};},
    evaluate:async()=>{trace.push('evaluate');return {total:1,results:[structuredClone(f.result)]};},
    recomputeGate:body=>({...body,gatePassed:body.results.every(row=>row.passed),failed:body.results.filter(row=>!row.passed).length}),
    assertInputsStable:async()=>{trace.push('inputs');},
    persist:async()=>{trace.push('persist');},recordFailure:async()=>{trace.push('failure');}};
}
test('requires approval before any capability/audit/slot/evaluation callback',async()=>{
  const f=fixture(),trace=[],o=options(f,trace);o.authorization.approvalReceipt=null;
  await assert.rejects(()=>runVersionedWorkflow(o));assert.deepEqual(trace,[]);
});
test('reserves once only after preflights, scores full proof, and checks source stability before persistence',async()=>{
  const f=fixture(),trace=[];const result=await runVersionedWorkflow(options(f,trace));
  assert.equal(result.gatePassed,true);assert.equal(result.results[0].versionedScoring.passed,true);
  assert.deepEqual(trace,['capability','audit','slot','evaluate','audit','inputs','persist']);
});
test('quality failure is terminal and provenance drift never persists a PASS',async()=>{
  const f=fixture(),trace=[];f.result.passed=false;
  const result=await runVersionedWorkflow(options(f,trace));assert.equal(result.gatePassed,false);
  assert.equal(trace.filter(x=>x==='evaluate').length,1);
  const g=fixture(),driftTrace=[],o=options(g,driftTrace);let reads=0;
  o.captureSourceAudit=async()=>{driftTrace.push('audit');const copy=structuredClone(g.sourceAudit);if(++reads===2)copy.sourceOwners[0].chunkTextSha256='0'.repeat(64);return copy;};
  await assert.rejects(()=>runVersionedWorkflow(o));assert.equal(driftTrace.includes('persist'),false);
  assert.equal(driftTrace.at(-1),'failure');assert.equal(driftTrace.filter(x=>x==='evaluate').length,1);
});
test('an unmet prior checkpoint holds only before the new slot is opened',async()=>{
  const f=fixture(),trace=[],o=options(f,trace);o.stage='difficult';
  o.assertPredecessor=async()=>{trace.push('predecessor');throw Error('Prior checkpoint not PASS');};
  await assert.rejects(()=>runVersionedWorkflow(o),/Prior checkpoint/);
  assert.deepEqual(trace,['capability','audit','predecessor']);
});
