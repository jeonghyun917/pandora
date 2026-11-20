// Read-only proposed payload identity. This CLI cannot call an evaluation endpoint.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {isDeepStrictEqual}=require('node:util');
const {loadEvalCases,selectEvalCases}=require('./lib/rag-eval-cases');
const {datasetHash,selectionHash}=require('./lib/rag-eval-provenance');
const {applyDeclaredCaseVersion}=require('./lib/rag-versioned-dataset');
const sha256=bytes=>require('node:crypto').createHash('sha256').update(bytes).digest('hex');
// Reviewed inputs for this fixed proposal, not caller-supplied assertions.
const REVIEWED={
  'it-compliance-penalty':{scorer:'qualified-approval',sha256:'d4e31cfd9e981bfd19cc7f078e47d3c8d5ed5ae576e08b0183c7f438d28e7fb2'},
  'ai-law-enforcement-date':{scorer:'reference-commencement',sha256:'5bd6a63773fe06cc24c9710907476ab3802c596e4b291c6fdadf9e72882a0ef7'},
};
const REVIEWED_SPLITS={difficult:'599e39fc4d3b82a1c9c996ba982f0856b0f4279b1f9de3cbbd9e211d6f2e3b78',
  holdout:'70a5057f4db3ba1c909a6de715382e90982e68e54d7b8eaaac376f7178557acc'};
function assertReviewedBindings(descriptor) {
  assert.deepEqual(descriptor.overrides.map(item=>item.id).sort(),Object.keys(REVIEWED).sort(),'reviewed binding IDs');
  assert.deepEqual(Object.keys(descriptor.sourceContracts).sort(),Object.values(REVIEWED).map(item=>item.scorer).sort(),'reviewed contract keys');
  for(const override of descriptor.overrides) {
    const reviewed=REVIEWED[override.id];
    assert.equal(override.scorer,reviewed.scorer,'reviewed case/scorer binding');
    assert.equal(sha256(JSON.stringify(descriptor.sourceContracts[reviewed.scorer])),reviewed.sha256,'reviewed source contract content');
  }
}
function assertReviewedSplit(name,bytes) {
  assert.ok(Object.hasOwn(REVIEWED_SPLITS,name),'reviewed split name');
  assert.equal(sha256(bytes),REVIEWED_SPLITS[name],'reviewed split bytes changed');
  return JSON.parse(bytes);
}
function loadVersionedDatasetProposal({root=path.resolve(__dirname,'..')}={}) {
  const resource=name=>path.join(root,'src/main/resources',name);
  const inputs=['rag-evaluation-cases.tsv','rag-evaluation-cases.generated.tsv','rag-answer-evaluation-oracles.tsv'].map(resource);
  const descriptorPath=path.join(root,'docs/evidence/rag-quality/evaluation-versions/qualified-reference-v2.json');
  const descriptor=JSON.parse(fs.readFileSync(descriptorPath,'utf8'));
  const baselineSha256=datasetHash(inputs);
  const baseline=loadEvalCases(inputs.slice(0,2),{answerOraclePath:inputs[2]});
  const proposed=applyDeclaredCaseVersion(baseline,baselineSha256,descriptor);
  const changed=proposed.filter((row,i)=>!isDeepStrictEqual(row,baseline[i])).map(row=>row.id);
  assert.deepEqual(changed,['it-compliance-penalty','ai-law-enforcement-date']);
  assertReviewedBindings(descriptor);
  const splits=['difficult','holdout'].map(name=>resource(`rag-retrieval-${name}-manifest.json`));
  const checkpointSizes={penalty:1};
  for(const [i,name] of ['difficult','holdout'].entries()) {
    const ids=assertReviewedSplit(name,fs.readFileSync(splits[i])).trainingCaseIds;
    assert.equal(ids.length,name==='difficult'?12:57);
    assert.equal(new Set(ids).size,ids.length);
    const before=selectEvalCases(baseline,{caseIds:ids}),after=selectEvalCases(proposed,{caseIds:ids});
    assert.deepEqual(after.map(x=>x.id),before.map(x=>x.id));
    assert.equal(after.length,ids.length);
    checkpointSizes[name]=after.length;
  }
  checkpointSizes.full=proposed.length;
  const bindingFiles=[...inputs,...splits,descriptorPath,__filename,path.join(__dirname,'lib/rag-versioned-dataset.js'),
    path.join(__dirname,'lib/rag-eval-cases.js'),path.join(__dirname,'lib/rag-eval-provenance.js'),
    path.join(__dirname,'lib/rag-qualified-example-scorer.js'),path.join(__dirname,'lib/rag-reference-commencement-scorer.js')];
  assert.equal(datasetHash(inputs),baselineSha256,'Frozen inputs changed during preparation');
  const report={version:descriptor.version,status:descriptor.status,baselineDatasetSha256:baselineSha256,
    count:proposed.length,unchangedCount:proposed.length-changed.length,changedIds:changed,
    baselineSelectionSha256:selectionHash(baseline),proposedSelectionSha256:selectionHash(proposed),
    preparationSha256:datasetHash(bindingFiles),checkpointSizes,subsetOrderPreserved:true,liveEligible:false};
  return {report,cases:proposed,descriptor};
}
function preflightVersionedDataset(options) {return loadVersionedDatasetProposal(options).report;}
if(require.main===module) console.log(JSON.stringify(preflightVersionedDataset(),null,2));
module.exports={preflightVersionedDataset,loadVersionedDatasetProposal,assertReviewedBindings,assertReviewedSplit};
