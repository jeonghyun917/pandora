const test=require('node:test'),assert=require('node:assert/strict'),http=require('node:http');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {postVersionedEvaluation,recordVersionedAttempt}=require('./lib/rag-versioned-transport');
test('versioned POST refuses redirects before retransmitting a question',async()=>{
  let redirected=0,received=0;const server=http.createServer((req,res)=>{
    req.resume();req.on('end',()=>{
      if(req.url==='/redirected'){redirected++;res.end('{}');return;}
      received++;res.writeHead(307,{Location:'/redirected'});res.end();
    });
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {await assert.rejects(postVersionedEvaluation(`http://127.0.0.1:${server.address().port}/evaluate`,
    {payload:{cases:[{id:'synthetic',question:'synthetic local only'}]},evaluationContractSha256:'a'.repeat(64)}));
    assert.equal(received,1);assert.equal(redirected,0);
  } finally {await new Promise(resolve=>server.close(resolve));}
});
test('completed response proof and each error retry survive independently before postflight',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-attempt-'));
  try {
    const checkpoint=path.join(root,'targeted-checkpoint.json'),proof={results:[{id:'synthetic',verificationEnvelope:{verifiedAnswer:'observed'}}]};
    const first=recordVersionedAttempt(checkpoint,1,{httpStatus:200,body:proof});
    const second=recordVersionedAttempt(checkpoint,2,{httpStatus:200,body:{results:[{id:'synthetic',resultMsg:'EVALUATION_ERROR'}]}});
    assert.deepEqual(JSON.parse(fs.readFileSync(first)).body,proof);assert.notEqual(first,second);
    assert.throws(()=>recordVersionedAttempt(checkpoint,1,{body:{}}),/EEXIST/);
    assert.deepEqual(JSON.parse(fs.readFileSync(first)).body,proof);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-attempt-'));fs.rmSync(root,{recursive:true,force:true});}
});
