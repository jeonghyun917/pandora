const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {buildCheckpointIdentity,resolveReportPaths,reserveVersionedEvaluationSlot,isCheckpointCompatible}=require('./lib/rag-candidate-provenance');
const {assertVersionedExecutionPreflight}=require('./lib/rag-versioned-evaluation');
const {fixture,HASH}=require('./test-support/versioned-evaluation-fixture');
function identity(f,jar=f.runtime.runtimeArtifactSha256) {
  return buildCheckpointIdentity({scope:'targeted',baseUrl:'http://localhost:8080',datasetHashValue:HASH,
    selectionHashValue:'b'.repeat(64),selectedCount:1,runtimeInfo:{...f.runtime,runtimeArtifactSha256:jar},
    baselineManifestId:'c'.repeat(64),evaluationContractSha256:HASH,evaluationCandidateSha256:jar});
}
test('candidate paths bind JAR not process, receipt or runner; identities cannot cross candidates',()=>{
  const f=fixture(),a=identity(f),b=identity(f,'d'.repeat(64));
  const first=resolveReportPaths('targeted',{},a);
  assert.match(first.outputPath,new RegExp('/candidates/'+f.runtime.runtimeArtifactSha256+'/'));
  assert.notEqual(first.outputPath,resolveReportPaths('targeted',{},b).outputPath);
  assert.equal(first.outputPath,resolveReportPaths('targeted',{}, {...a,runtimeInstanceId:'restart',baselineManifestId:'e'.repeat(64)}).outputPath);
  assert.equal(isCheckpointCompatible({checkpointIdentity:a},{...a,evaluationCandidateSha256:'d'.repeat(64)}),false);
  const legacy={...a};delete legacy.evaluationCandidateSha256;
  assert.equal(isCheckpointCompatible({checkpointIdentity:a},legacy),false);
  assert.throws(()=>resolveReportPaths('targeted',{}, {...a,evaluationCandidateSha256:'../bad'}));
  assert.throws(()=>resolveReportPaths('targeted',{}, {...a,evaluationCandidateSha256:'d'.repeat(64)}));
});
test('legacy consumed owner remains closed while a distinct candidate reserves only once',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-candidate-'));
  try {
    const f=fixture(),old=identity(f),legacy={...old};delete legacy.evaluationCandidateSha256;
    const prior=require('./lib/rag-eval-provenance').reserveVersionedEvaluationSlot('targeted',legacy,{root,env:{}});
    assert.throws(()=>reserveVersionedEvaluationSlot('targeted',identity(f,'d'.repeat(64)),{root,env:{}}),/legacy|owner|terminal/i);
    const bytes=JSON.stringify({completionStatus:'ERROR',gatePassed:false,checkpointIdentity:legacy});
    fs.writeFileSync(path.resolve(root,prior.outputPath),bytes);
    assert.throws(()=>reserveVersionedEvaluationSlot('targeted',old,{root,env:{}}),/consumed|candidate/i);
    const next=identity(f,'d'.repeat(64));
    assert.doesNotThrow(()=>reserveVersionedEvaluationSlot('targeted',next,{root,env:{}}));
    assert.throws(()=>reserveVersionedEvaluationSlot('targeted',{...next,runtimeInstanceId:'restart',baselineManifestId:'e'.repeat(64)},{root,env:{}}),/started|existing/i);
    const stripped={...next};delete stripped.evaluationCandidateSha256;
    assert.throws(()=>reserveVersionedEvaluationSlot('targeted',stripped,{root,env:{}}),/consumed|candidate/i);
    assert.equal(fs.readFileSync(path.resolve(root,prior.outputPath),'utf8'),bytes);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-candidate-'));fs.rmSync(root,{recursive:true,force:true});}
});

test('a predecessor must occupy the same candidate namespace and carry matching candidate proof',()=>{
  const {selectVersionedStage}=require('./lib/rag-versioned-evaluation');
  const {selectionHash}=require('./lib/rag-eval-provenance');
  const {assertPriorVersionedCheckpoint}=require('./lib/rag-versioned-checkpoint');
  const {buildBlockingGates}=require('./lib/rag-eval-gates');
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-candidate-'));
  try {
    const f=fixture(),current=identity(f),cases=selectVersionedStage(f.catalogue,'penalty');
    const prior={...current,selectionHash:selectionHash(cases),selectedCaseCount:1};
    const row={...f.result,unsafeSemanticShadowDisagreementCount:0,versionedScoring:{kind:'qualified-approval',passed:true}};
    const body={checkpointIdentity:prior,versionedStage:'penalty',evaluationContractSha256:HASH,gatePassed:true,total:1,passed:1,failed:0,minimumPassed:1,
      blockingFailureIds:[],unsafeSemanticShadowDisagreementCount:0,results:[row],blockingGates:{...buildBlockingGates([row]),unsafeSemanticShadow:{total:1,failed:0,gatePassed:true}}};
    const target=path.resolve(root,resolveReportPaths('targeted',{},prior).outputPath);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,JSON.stringify(body));
    const args={root,catalogue:f.catalogue,stage:'difficult',identity:current};
    assert.doesNotThrow(()=>assertPriorVersionedCheckpoint(args));
    assert.throws(()=>assertPriorVersionedCheckpoint({...args,identity:identity(f,'d'.repeat(64))}),/Prior checkpoint/);
    delete body.checkpointIdentity.evaluationCandidateSha256;fs.writeFileSync(target,JSON.stringify(body));
    assert.throws(()=>assertPriorVersionedCheckpoint(args),/identity/);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-candidate-'));fs.rmSync(root,{recursive:true,force:true});}
});
test('candidate policy cannot authorize another JAR or an unknown checkpoint policy',()=>{
  const f=fixture(),args={catalogue:f.catalogue,evaluationContractSha256:HASH,...f.authorization,currentRuntimeInfo:f.runtime};
  args.approvalRequest={...args.approvalRequest,checkpointPolicy:'candidate-once-v1',evaluationCandidateSha256:f.runtime.runtimeArtifactSha256};
  assert.doesNotThrow(()=>assertVersionedExecutionPreflight(args));
  for(const patch of [{checkpointPolicy:'restart-once'},{evaluationCandidateSha256:'d'.repeat(64)},{checkpointPolicy:undefined}])
    assert.throws(()=>assertVersionedExecutionPreflight({...args,approvalRequest:{...args.approvalRequest,...patch}}),/candidate|policy/i);
});

test('legacy/new interleaving cannot reserve the same JAR twice',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-candidate-')),original=fs.writeFileSync;
  try {
    const f=fixture(),candidate=identity(f),legacy={...candidate};delete legacy.evaluationCandidateSha256;
    let interleaved=false;const results=[];
    const reserve=value=>{try{reserveVersionedEvaluationSlot('targeted',value,{root,env:{}});results.push('started');}catch{results.push('refused');}};
    // Preserve real filesystem writes, scheduling the other reservation precisely
    // after the first path's checks but before its atomic marker write.
    fs.writeFileSync=(filename,...args)=>{
      if(!interleaved && String(filename).endsWith('-started.json')){interleaved=true;reserve(legacy);}
      return original(filename,...args);
    };
    reserve(candidate);
    assert.equal(interleaved,true);assert.equal(results.filter(value=>value==='started').length,1);
    assert.equal(results.filter(value=>value==='refused').length,1);
  } finally {fs.writeFileSync=original;assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-candidate-'));fs.rmSync(root,{recursive:true,force:true});}
});

test('conflicting legacy owners and wrong scopes cannot create a fresh candidate slot',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-candidate-'));
  try {
    const f=fixture(),a=identity(f),b=identity(f,'d'.repeat(64)),legacy={...a};delete legacy.evaluationCandidateSha256;
    const prior=require('./lib/rag-eval-provenance').reserveVersionedEvaluationSlot('targeted',legacy,{root,env:{}});
    const write=(filename,owner)=>fs.writeFileSync(path.resolve(root,filename),JSON.stringify({completionStatus:'ERROR',gatePassed:false,checkpointIdentity:owner}));
    const other={...legacy,runtimeArtifactSha256:b.runtimeArtifactSha256};
    write(prior.outputPath,other);write(prior.checkpointPath,legacy);
    assert.throws(()=>reserveVersionedEvaluationSlot('targeted',a,{root,env:{}}),/legacy|owner|conflict|ambiguous/i);
    fs.unlinkSync(path.resolve(root,prior.checkpointPath));write(prior.outputPath,{...legacy,runScope:'full'});
    assert.throws(()=>reserveVersionedEvaluationSlot('targeted',b,{root,env:{}}),/legacy|scope|ambiguous/i);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-candidate-'));fs.rmSync(root,{recursive:true,force:true});}
});
