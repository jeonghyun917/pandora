const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{createHash}=require('node:crypto');
const {loadVersionedAuthorization,versionedRunnerHash}=require('./lib/rag-versioned-authorization');
const {sourceAuditToolHash}=require('./capture-versioned-source-audit');
const {fixture,HASH}=require('./test-support/versioned-evaluation-fixture');
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
function files(root) {
  const f=fixture(),write=(name,value)=>{const bytes=Buffer.from(JSON.stringify(value));fs.writeFileSync(path.join(root,name),bytes);return hash(bytes);};
  f.authorization.sourceAudit.auditToolSha256=sourceAuditToolHash();
  f.authorization.sourceAudit.runtimeInfo.runtimeInstanceId='before-registration';
  const sourceHash=write('source.json',f.authorization.sourceAudit);
  const request={...f.authorization.approvalRequest,sourceAuditSha256:sourceHash,sourceAuditReference:'source.json',runnerSha256:versionedRunnerHash()};
  const requestHash=write('request.json',request);
  const manifest={...f.authorization.executionManifest,approvalRequestSha256:requestHash,approvalRequestReference:'request.json',
    sourceAuditReference:'source.json',sourceAuditSha256:sourceHash,runnerSha256:request.runnerSha256};
  write('manifest.json',manifest);write('receipt.json',{...f.authorization.approvalReceipt,approvalRequestSha256:requestHash});
  return {f,write,manifest,request};
}
test('binds actual artifact bytes and permits only a same-payload activation restart',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-auth-'));
  try {
    const {f,write,manifest}=files(root);
    const input={root,catalogue:f.catalogue,evaluationContractSha256:HASH,executionManifestPath:'manifest.json',approvalReceiptPath:'receipt.json'};
    const before=loadVersionedAuthorization(input);
    assert.match(before.identitySha256,/^[a-f0-9]{64}$/);
    assert.equal(before.currentRunnerSha256,versionedRunnerHash());
    manifest.runtimeInfo.runtimeInstanceId='after-activation';write('manifest.json',manifest);
    const after=loadVersionedAuthorization(input);
    assert.notEqual(after.identitySha256,before.identitySha256);
    assert.equal(after.approvalRequestSha256,before.approvalRequestSha256);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-auth-'));fs.rmSync(root,{recursive:true,force:true});}
});
test('missing receipt, changed source bytes, escaping references and changed corpus cannot authorize execution',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-auth-'));
  try {
    const {f}=files(root);const input={root,catalogue:f.catalogue,evaluationContractSha256:HASH,executionManifestPath:'manifest.json',approvalReceiptPath:'receipt.json'};
    assert.throws(()=>loadVersionedAuthorization({...input,approvalReceiptPath:''}),/approval|artifact|path/i);
    fs.writeFileSync(path.join(root,'source.json'),'{}');assert.throws(()=>loadVersionedAuthorization(input),/source|hash|artifact/i);
    const repaired=files(root);repaired.manifest.sourceAuditReference='../outside.json';repaired.write('manifest.json',repaired.manifest);
    assert.throws(()=>loadVersionedAuthorization(input),/relative|within|artifact/i);
    const corpus=files(root);corpus.manifest.runtimeInfo.lawDatabaseContentFingerprint='0'.repeat(64);corpus.write('manifest.json',corpus.manifest);
    assert.throws(()=>loadVersionedAuthorization(input),/corpus|source|payload/i);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-auth-'));fs.rmSync(root,{recursive:true,force:true});}
});

test('an internally hash-consistent receipt cannot approve a contradictory payload summary',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-auth-'));
  try {
    for(const patch of [{questionCount:1},{changedCaseIds:[]},{baselineSha256:'0'.repeat(64)},
      {kind:'different-payload'},{requiresExplicitApproval:false}]) {
      const {f,write,request,manifest}=files(root);
      Object.assign(request,patch);const requestHash=write('request.json',request);
      manifest.approvalRequestSha256=requestHash;write('manifest.json',manifest);
      write('receipt.json',{...f.authorization.approvalReceipt,approvalRequestSha256:requestHash});
      assert.throws(()=>loadVersionedAuthorization({root,catalogue:f.catalogue,evaluationContractSha256:HASH,
        executionManifestPath:'manifest.json',approvalReceiptPath:'receipt.json'}),/payload|summary|approval/i);
    }
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-auth-'));fs.rmSync(root,{recursive:true,force:true});}
});
