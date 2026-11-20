const test=require('node:test'),assert=require('node:assert/strict');
const {main}=require('./prepare-versioned-execution');
test('preparation rejects malformed modes and missing exact receipt before runtime network access',async()=>{
  let calls=0;const originalFetch=global.fetch,originalHash=process.env.RAG_EVAL_CONTRACT_SHA256;
  global.fetch=async()=>{calls++;throw Error('unexpected network');};
  process.env.RAG_EVAL_CONTRACT_SHA256='8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43';
  try {
    await assert.rejects(main(['unknown']),/Expected/);
    await assert.rejects(main(['execution-manifest','missing-request.json','missing-receipt.json']),/ENOENT/);
    assert.equal(calls,0);
  } finally {global.fetch=originalFetch;if(originalHash===undefined)delete process.env.RAG_EVAL_CONTRACT_SHA256;else process.env.RAG_EVAL_CONTRACT_SHA256=originalHash;}
});
