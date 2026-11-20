const test=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {sealProposedCatalogue}=require('./lib/rag-versioned-catalogue');
test('seals an exact inactive1003 catalogue without a registration or permission claim',()=>{
  const sealed=sealProposedCatalogue();
  assert.equal(crypto.createHash('sha256').update(sealed.bytes).digest('hex'),sealed.sha256);
  const catalogue=JSON.parse(sealed.bytes);
  assert.equal(catalogue.kind,'pandora-versioned-evaluation');
  assert.equal(catalogue.schemaVersion,1);
  assert.equal(catalogue.cases.length,1003);
  assert.equal(catalogue.cases.some(c=>Object.hasOwn(c,'failureTaxonomy')),false);
  assert.deepEqual(catalogue.scoringOverrides,{'it-compliance-penalty':'qualified-approval','ai-law-enforcement-date':'reference-commencement'});
  assert.match(catalogue.preparationSha256,/^[a-f0-9]{64}$/);
  assert.equal(catalogue.authorization,'requires-explicit-registration-and-payload-approval');
  assert.equal(sealed.liveEligible,false);
  assert.deepEqual(sealProposedCatalogue().bytes,sealed.bytes);
});
