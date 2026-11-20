const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{createHash}=require('node:crypto');
const {prepareApprovalRequest,prepareExecutionManifest}=require('./lib/rag-versioned-artifacts');
const {sourceAuditToolHash}=require('./capture-versioned-source-audit');
const {loadVersionedAuthorization}=require('./lib/rag-versioned-authorization');
const {fixture,HASH}=require('./test-support/versioned-evaluation-fixture');
test('prepares a stable approval request but never a receipt or registration',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-artifacts-'));
  try {
    const f=fixture();f.sourceAudit.auditToolSha256=sourceAuditToolHash();fs.writeFileSync(path.join(root,'source.json'),JSON.stringify(f.sourceAudit));
    const input={root,catalogue:f.catalogue,evaluationContractSha256:HASH,sourceAuditPath:'source.json'};
    const request=prepareApprovalRequest(input);
    assert.equal(request.questionCount,1003);assert.equal(request.requiresExplicitApproval,true);
    assert.equal(request.checkpointPolicy,'candidate-once-v1');
    assert.equal(request.evaluationCandidateSha256,f.runtime.runtimeArtifactSha256);
    assert.equal(Object.hasOwn(request,'approvalReceipt'),false);assert.equal(Object.hasOwn(request,'approvedAt'),false);
    assert.deepEqual(prepareApprovalRequest(input),request);assert.deepEqual(fs.readdirSync(root),['source.json']);
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-artifacts-'));fs.rmSync(root,{recursive:true,force:true});}
});
test('execution manifest requires an exact receipt and accepts only the approved payload/artifact',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-artifacts-'));
  try {
    const f=fixture();f.sourceAudit.auditToolSha256=sourceAuditToolHash();
    const write=(name,value)=>{const bytes=Buffer.from(JSON.stringify(value));fs.writeFileSync(path.join(root,name),bytes);return createHash('sha256').update(bytes).digest('hex');};
    write('source.json',f.sourceAudit);
    const request=prepareApprovalRequest({root,catalogue:f.catalogue,evaluationContractSha256:HASH,sourceAuditPath:'source.json'});
    const requestHash=write('request.json',request);
    const input={root,catalogue:f.catalogue,evaluationContractSha256:HASH,approvalRequestPath:'request.json',approvalReceiptPath:'receipt.json',
      currentRuntimeInfo:{...f.runtime,runtimeInstanceId:'after-approved-registration'}};
    assert.throws(()=>prepareExecutionManifest(input),/ENOENT|approval|receipt/i);
    write('receipt.json',{schemaVersion:1,approvalRequestSha256:requestHash,authorizationReference:'synthetic local test only',approvedAt:'2026-11-20T00:00:00Z'});
    const manifest=prepareExecutionManifest(input);write('execution.json',manifest);
    assert.doesNotThrow(()=>loadVersionedAuthorization({root,catalogue:f.catalogue,evaluationContractSha256:HASH,
      executionManifestPath:'execution.json',approvalReceiptPath:'receipt.json'}));
    assert.throws(()=>prepareExecutionManifest({...input,currentRuntimeInfo:{...input.currentRuntimeInfo,runtimeArtifactSha256:'0'.repeat(64)}}));
  } finally {assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-artifacts-'));fs.rmSync(root,{recursive:true,force:true});}
});
