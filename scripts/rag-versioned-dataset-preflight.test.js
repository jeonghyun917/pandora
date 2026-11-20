const test=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {preflightVersionedDataset}=require('./rag-versioned-dataset-preflight');
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
