const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {preflightVersionedEvaluationContract:preflight}=require('./lib/rag-evaluation-contract-preflight');
const hash='a'.repeat(64);
const descriptor={schemaVersion:1,contractHeader:'X-Pandora-Evaluation-Contract-SHA256',registeredContracts:[hash]};
async function fixture(t,body,status=200) {
  const requests=[];
  const server=http.createServer((req,res)=>{requests.push({method:req.method,url:req.url});res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(body));});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  return {baseUrl:`http://127.0.0.1:${server.address().port}`,requests};
}
test('preflights a known contract through one read-only capability request',async t=>{
  const f=await fixture(t,descriptor);
  const result=await preflight({...f,evaluationContractSha256:hash});
  assert.equal(result.evaluationContractSha256,hash);
  assert.deepEqual(f.requests,[{method:'GET',url:'/api/law-data/ai/debug/evaluation-contracts'}]);
});
test('unregistered, malformed and unavailable capabilities never issue evaluation requests or retries',async t=>{
  for(const [body,status] of [[{...descriptor,registeredContracts:[]},200],[{...descriptor,registeredContracts:[hash,hash]},200],
    [{...descriptor,contractHeader:'Other'},200],[{...descriptor,schemaVersion:2},200],[descriptor,404]]) {
    const f=await fixture(t,body,status);
    await assert.rejects(preflight({...f,evaluationContractSha256:hash}));
    assert.deepEqual(f.requests,[{method:'GET',url:'/api/law-data/ai/debug/evaluation-contracts'}]);
  }
});
test('invalid contract or credential-bearing base URL is rejected before any request',async()=>{
  let calls=0;const fetchImpl=async()=>{calls++;throw Error('must not send');};
  for(const input of [{baseUrl:'http://127.0.0.1:8080',evaluationContractSha256:'draft'},
    {baseUrl:'http://user:secret@127.0.0.1:8080',evaluationContractSha256:hash},
    {baseUrl:'file:///tmp',evaluationContractSha256:hash}])
    await assert.rejects(preflight({...input,fetchImpl}));
  assert.equal(calls,0);
});
