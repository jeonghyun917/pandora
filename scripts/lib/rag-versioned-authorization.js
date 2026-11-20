const fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const assert=require('node:assert/strict');
const {datasetHash}=require('./rag-eval-provenance');
const {assertVersionedExecutionPreflight,validateCapturedSourceAudit}=require('./rag-versioned-evaluation');
const {sourceAuditToolHash}=require('../capture-versioned-source-audit');
const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
function versionedRunnerHash() {
  const scripts=path.resolve(__dirname,'..');
  return datasetHash([__filename,path.join(scripts,'rag-eval-gate.js'),path.join(scripts,'rag-eval-versioned.js'),path.join(scripts,'capture-versioned-source-audit.js'),
    ...['rag-versioned-transport','rag-versioned-artifacts','rag-versioned-workflow','rag-versioned-evaluation','rag-versioned-checkpoint','rag-evaluation-contract-preflight','rag-eval-provenance',
      'rag-eval-gates','rag-eval-cases','rag-qualified-example-scorer','rag-reference-commencement-scorer'].map(name=>path.join(__dirname,name+'.js'))]);
}
function readBoundJson(root,relative) {
  assert.ok(typeof relative==='string' && relative.trim() && !path.isAbsolute(relative),'Relative approval artifact path required');
  const base=fs.realpathSync(root),candidate=path.resolve(base,relative);
  const within=filename=>{const rel=path.relative(base,filename);return rel!=='..' && !rel.startsWith('..'+path.sep) && !path.isAbsolute(rel);};
  assert.ok(within(candidate),'Approval artifact must stay within the selected root');
  const stat=fs.lstatSync(candidate);
  assert.ok(stat.isFile() && stat.size>0 && stat.size<=262144 && within(fs.realpathSync(candidate)),'Invalid approval artifact');
  const bytes=fs.readFileSync(candidate);
  return {value:JSON.parse(bytes),sha256:sha256(bytes)};
}
function loadVersionedAuthorization({root=path.resolve(__dirname,'../..'),catalogue,evaluationContractSha256,
    executionManifestPath,approvalReceiptPath}={}) {
  const manifest=readBoundJson(root,executionManifestPath),receipt=readBoundJson(root,approvalReceiptPath);
  const request=readBoundJson(root,manifest.value.approvalRequestReference);
  assert.equal(request.sha256,manifest.value.approvalRequestSha256,'Approval request artifact hash mismatch');
  const audit=readBoundJson(root,manifest.value.sourceAuditReference);
  assert.equal(audit.sha256,manifest.value.sourceAuditSha256,'Source audit artifact hash mismatch');
  assert.equal(request.value.sourceAuditReference,manifest.value.sourceAuditReference,'Approved source artifact differs');
  assert.equal(audit.value.auditToolSha256,sourceAuditToolHash(),'Source audit tooling changed');
  validateCapturedSourceAudit({catalogue,evaluationContractSha256,audit:audit.value,before:audit.value.runtimeInfo,after:audit.value.runtimeInfo});
  assert.deepEqual(manifest.value.sourceOwners,audit.value.sourceOwners,'Approved source owner snapshot differs');
  // Registration may restart an unchanged artifact. Instance/path/timestamps are
  // fixed by the later execution manifest, while approved payload data stays fixed.
  for(const key of ['runtimeArtifactSha256','runtimeConfigSha256','indexVersion','indexRevision','lexicalRevision',
    'embeddingModel','answerModel','lawCollection','ragCollection','lawDatabaseIndexedCount','ragDatabaseIndexedCount',
    'lawDatabaseContentFingerprint','ragDatabaseContentFingerprint'])
    assert.equal(manifest.value.runtimeInfo?.[key],audit.value.runtimeInfo?.[key],'Approved source/corpus payload changed');
  const authorization={executionManifest:manifest.value,executionManifestSha256:manifest.sha256,
    approvalRequest:request.value,approvalRequestSha256:request.sha256,
    approvalReceipt:receipt.value,approvalReceiptSha256:receipt.sha256,
    sourceAudit:audit.value,sourceAuditSha256:audit.sha256,currentRunnerSha256:versionedRunnerHash(),
    identitySha256:sha256(JSON.stringify([manifest.sha256,request.sha256,receipt.sha256,audit.sha256]))};
  assertVersionedExecutionPreflight({catalogue,evaluationContractSha256,...authorization,currentRuntimeInfo:manifest.value.runtimeInfo});
  return authorization;
}
module.exports={loadVersionedAuthorization,versionedRunnerHash,readBoundJson};
