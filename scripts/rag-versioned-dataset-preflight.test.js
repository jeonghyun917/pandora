const test=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {preflightVersionedDataset,assertReviewedBindings,assertReviewedSplit}=require('./rag-versioned-dataset-preflight');
test('rejects cross-wired case/scorer and missing or changed reviewed proof contracts',()=>{
  assert.equal(typeof assertReviewedBindings,'function');
  const descriptor=require('../docs/evidence/rag-quality/evaluation-versions/qualified-reference-v2.json');
  for(const change of [d=>{d.overrides.reverse();d.overrides[0].scorer='qualified-approval';d.overrides[1].scorer='reference-commencement';},
    d=>{d.sourceContracts['qualified-approval']=null;},
    d=>{d.sourceContracts['reference-commencement'].owner.chunkId=1;},
    d=>{d.sourceContracts['qualified-approval'].remedy.anchors.trigger='조건 삭제';}]) {
    const bad=structuredClone(descriptor);change(bad);
    assert.throws(()=>assertReviewedBindings(bad),/reviewed|binding|contract/i);
  }
});
test('rejects changed original split membership before claiming preservation',()=>{
  assert.equal(typeof assertReviewedSplit,'function');
  const fs=require('node:fs');
  const original=fs.readFileSync(path.resolve(__dirname,'../src/main/resources/rag-retrieval-holdout-manifest.json'));
  const changed=JSON.parse(original); changed.trainingCaseIds[changed.trainingCaseIds.indexOf('it-compliance-penalty')]='egov-preliminary-review-target';
  assert.throws(()=>assertReviewedSplit('holdout',Buffer.from(JSON.stringify(changed))),/reviewed|split/i);
});
test('preflights the actual frozen1003 dataset with exactly two inactive changes',()=>{
  const report=preflightVersionedDataset({root:path.resolve(__dirname,'..')});
  assert.equal(report.baselineDatasetSha256,'6b85990a6395c501138628877d4c9efda61c218afde4d0ab5a886f3588ce044c');
  assert.equal(report.count,1003);
  assert.equal(report.unchangedCount,1001);
  assert.deepEqual(report.changedIds,['it-compliance-penalty','ai-law-enforcement-date']);
  assert.deepEqual(report.checkpointSizes,{penalty:1,difficult:12,holdout:57,full:1003});
  assert.equal(report.liveEligible,false);
  assert.match(report.preparationSha256,/^[a-f0-9]{64}$/);
  assert.equal(report.subsetOrderPreserved,true);
});
