// Read-only proposed payload identity. This CLI cannot call an evaluation endpoint.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {isDeepStrictEqual}=require('node:util');
const {loadEvalCases,selectEvalCases}=require('./lib/rag-eval-cases');
const {datasetHash,selectionHash}=require('./lib/rag-eval-provenance');
const {applyDeclaredCaseVersion}=require('./lib/rag-versioned-dataset');
function preflightVersionedDataset({root=path.resolve(__dirname,'..')}={}) {
  const resource=name=>path.join(root,'src/main/resources',name);
  const inputs=['rag-evaluation-cases.tsv','rag-evaluation-cases.generated.tsv','rag-answer-evaluation-oracles.tsv'].map(resource);
  const descriptorPath=path.join(root,'docs/evidence/rag-quality/evaluation-versions/qualified-reference-v2.json');
  const descriptor=JSON.parse(fs.readFileSync(descriptorPath,'utf8'));
  const baselineSha256=datasetHash(inputs);
  const baseline=loadEvalCases(inputs.slice(0,2),{answerOraclePath:inputs[2]});
  const proposed=applyDeclaredCaseVersion(baseline,baselineSha256,descriptor);
  const changed=proposed.filter((row,i)=>!isDeepStrictEqual(row,baseline[i])).map(row=>row.id);
  assert.deepEqual(changed,['it-compliance-penalty','ai-law-enforcement-date']);
  assert.deepEqual(descriptor.overrides.map(item=>item.scorer),['qualified-approval','reference-commencement']);
  assert.deepEqual(Object.keys(descriptor.sourceContracts),['qualified-approval','reference-commencement']);
  const splits=['difficult','holdout'].map(name=>resource(`rag-retrieval-${name}-manifest.json`));
  const checkpointSizes={penalty:1};
  for(const [i,name] of ['difficult','holdout'].entries()) {
    const ids=JSON.parse(fs.readFileSync(splits[i],'utf8')).trainingCaseIds;
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
  return {version:descriptor.version,status:descriptor.status,baselineDatasetSha256:baselineSha256,
    count:proposed.length,unchangedCount:proposed.length-changed.length,changedIds:changed,
    baselineSelectionSha256:selectionHash(baseline),proposedSelectionSha256:selectionHash(proposed),
    preparationSha256:datasetHash(bindingFiles),checkpointSizes,subsetOrderPreserved:true,liveEligible:false};
}
if(require.main===module) console.log(JSON.stringify(preflightVersionedDataset(),null,2));
module.exports={preflightVersionedDataset};
