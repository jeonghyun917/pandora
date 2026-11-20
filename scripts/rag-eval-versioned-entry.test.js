const test=require('node:test'),assert=require('node:assert/strict');
const path=require('node:path'),http=require('node:http');
const {spawnSync,spawn}=require('node:child_process');
const entry=path.resolve(__dirname,'rag-eval-versioned.js');
test('dedicated entry cannot fall into a legacy full evaluation when flags are absent',()=>{
  const env={...process.env};delete env.RAG_EVAL_CONTRACT_SHA256;
  const result=spawnSync(process.execPath,[entry],{env,encoding:'utf8',timeout:5000});
  assert.equal(result.status,1);assert.match(result.stderr,/RAG_EVAL_CONTRACT_SHA256/);
  assert.equal(result.stdout.includes('running batch'),false);
});
test('missing bound authorization artifacts fail before any runtime GET or evaluation POST',async()=>{
  let requests=0;const server=http.createServer((req,res)=>{requests++;res.writeHead(500);res.end('{}');});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const env={...process.env,RAG_EVAL_BASE_URL:`http://127.0.0.1:${server.address().port}`,
      RAG_EVAL_CONTRACT_SHA256:'8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43',RAG_EVAL_STAGE:'penalty',
      RAG_EVAL_EXECUTION_MANIFEST:'.superpowers/missing-versioned-execution.json',RAG_EVAL_PAYLOAD_APPROVAL:'.superpowers/missing-versioned-approval.json',
      RAG_EVAL_RESUME:'0',RAG_EVAL_CASE_IDS:'',RAG_EVAL_CASE_LIMIT:'0',RAG_EVAL_GATE_PROFILE:'release',RAG_EVAL_ERROR_RETRIES:'3',
      RAG_EVAL_OUTPUT:'',RAG_EVAL_REPORT:'',RAG_EVAL_CHECKPOINT:''};
    const child=spawn(process.execPath,[entry],{env,stdio:['ignore','pipe','pipe']});let stderr='';child.stderr.on('data',chunk=>{stderr+=chunk;});
    const code=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{child.kill();reject(Error('Versioned entry stalled'));},10000);
      child.on('error',error=>{clearTimeout(timer);reject(error);});child.on('exit',code=>{clearTimeout(timer);resolve(code);});});
    assert.equal(code,1);assert.match(stderr,/artifact|ENOENT|approval|manifest/i);assert.equal(requests,0);
  } finally {await new Promise(resolve=>server.close(resolve));}
});
