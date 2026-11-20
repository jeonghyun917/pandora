const test=require('node:test'),assert=require('node:assert/strict');
const {captureVersionedSourceAudit}=require('./capture-versioned-source-audit');
const {loadVersionedCatalogue}=require('./lib/rag-versioned-evaluation');
const HASH='8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43';
test('captures only a read-only local probe between two runtime GETs, never an evaluation',async()=>{
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256:HASH});
  const runtime={runtimeArtifactSha256:'a'.repeat(64),runtimeInstanceId:'instance',runtimeConfigSha256:'b'.repeat(64),
    qdrantReady:true,qdrantSearchFailureCount:0,lawDatabaseIndexedCount:10,ragDatabaseIndexedCount:20,
    lawDatabaseContentFingerprint:'c'.repeat(64),ragDatabaseContentFingerprint:'d'.repeat(64)};
  const a=catalogue.sourceContracts['qualified-approval'],r=catalogue.sourceContracts['reference-commencement'];
  const owners=[{...a.qualification.owner,edition:a.indexedEdition},{...a.remedy.owner,edition:a.indexedEdition},
    {...r.owner,edition:r.edition}].map(owner=>({...owner,chunkVersion:1,chunkTextSha256:'e'.repeat(64),
      effectiveStatus:'CURRENT',activationStatus:'ACTIVE',useYn:'Y',qualityStatus:'PASS'}));
  const calls=[];
  const result=await captureVersionedSourceAudit({evaluationContractSha256:HASH,classpath:'local-fixture',
    fetchImpl:async(url,options)=>{calls.push(url.pathname);assert.equal(options.method,'GET');
      assert.equal(url.pathname,'/api/law-data/ai/debug/runtime-info');
      return new Response(JSON.stringify(runtime),{status:200,headers:{'content-type':'application/json'}});},
    execFileSyncImpl:(command,args,options)=>{
      calls.push(command);
      if(command==='javac')return '';
      assert.equal(command,'java');const input=JSON.parse(options.input);
      assert.equal(input.evaluationContractSha256,HASH);assert.equal(Object.hasOwn(input,'cases'),false);
      return JSON.stringify({schemaVersion:1,evaluationContractSha256:HASH,auditMode:'read-only-db-consistent-snapshot',
        capturedAt:'2026-11-20T00:00:00Z',sourceOwners:owners,lawDatabaseIndexedCount:10,ragDatabaseIndexedCount:20,
        lawDatabaseContentFingerprint:runtime.lawDatabaseContentFingerprint,ragDatabaseContentFingerprint:runtime.ragDatabaseContentFingerprint});
    }});
  assert.equal(result.sourceOwners.length,3);assert.match(result.auditToolSha256,/^[a-f0-9]{64}$/);
  assert.deepEqual(calls,['/api/law-data/ai/debug/runtime-info','javac','java','/api/law-data/ai/debug/runtime-info']);
});
