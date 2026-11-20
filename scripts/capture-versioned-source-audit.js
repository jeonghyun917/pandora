const fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const {execFileSync}=require('node:child_process');
const {loadVersionedCatalogue,validateCapturedSourceAudit}=require('./lib/rag-versioned-evaluation');
const {datasetHash}=require('./lib/rag-eval-provenance');
const {writeSealedCatalogue}=require('./seal-rag-evaluation-catalogue');
function sourceAuditToolHash(root=path.resolve(__dirname,'..')) {
  return datasetHash([__filename,path.join(root,'scripts/probes/VersionedSourceAuditProbe.java'),
    path.join(root,'scripts/lib/rag-versioned-evaluation.js'),path.join(root,'pom.xml'),
    ...['RagDocumentMapper','RagRecoveryMapper','SemanticLexicalMapper','LawChunkMapper'].map(name=>path.join(root,`src/main/resources/mapper/law/${name}.xml`))]);
}
async function readRuntimeInfo({baseUrl='http://127.0.0.1:8080',fetchImpl=fetch}={}) {
  const base=new URL(baseUrl);
  if(!['http:','https:'].includes(base.protocol) || base.username || base.password || base.pathname!=='/' || base.search || base.hash)
    throw Error('Expected credential-free runtime origin');
    const response=await fetchImpl(new URL('/api/law-data/ai/debug/runtime-info',base),{
      method:'GET',headers:{Accept:'application/json'},redirect:'error',signal:AbortSignal.timeout(5000)});
    if(response.status!==200 || !response.headers.get('content-type')?.startsWith('application/json'))throw Error('Read-only runtime unavailable');
    const text=await response.text();if(text.length>65536)throw Error('Oversized runtime response');
    return {...JSON.parse(text),source:'server'};
}
async function captureVersionedSourceAudit({root=path.resolve(__dirname,'..'),evaluationContractSha256,
    baseUrl='http://127.0.0.1:8080',fetchImpl=fetch,execFileSyncImpl=execFileSync,classpath}={}) {
  const {catalogue}=loadVersionedCatalogue({root,evaluationContractSha256});
  const before=await readRuntimeInfo({baseUrl,fetchImpl});
  const cp=classpath??[path.join(root,'target/classes'),fs.readFileSync(path.join(root,'target/probe-classpath.txt'),'utf8').trim()].join(path.delimiter);
  const output=path.join(root,'target/offline-probe-classes');fs.mkdirSync(output,{recursive:true});
  const probe=path.join(root,'scripts/probes/VersionedSourceAuditProbe.java');
  execFileSyncImpl('javac',['-encoding','UTF-8','-cp',cp,'-d',output,probe],{cwd:root,encoding:'utf8',timeout:30000,stdio:'pipe'});
  const audit=JSON.parse(execFileSyncImpl('java',['-Dfile.encoding=UTF-8','-cp',output+path.delimiter+cp,'VersionedSourceAuditProbe'],{
    cwd:root,encoding:'utf8',timeout:20000,maxBuffer:131072,
    input:JSON.stringify({evaluationContractSha256,sourceContracts:catalogue.sourceContracts,runtimeInfo:before}),stdio:'pipe'}));
  const after=await readRuntimeInfo({baseUrl,fetchImpl});
  validateCapturedSourceAudit({catalogue,evaluationContractSha256,audit,before,after});
  return {...audit,auditToolSha256:sourceAuditToolHash(root),probeClasspathSha256:createHash('sha256').update(cp).digest('hex'),runtimeInfo:after};
}
if(require.main===module) {
  (async()=>{
    const args=process.argv.slice(2);if(args.length>1 || args.length===1 && args[0]!=='--write')throw Error('Expected only optional --write');
    const audit=await captureVersionedSourceAudit({evaluationContractSha256:process.env.RAG_EVAL_CONTRACT_SHA256,
      baseUrl:process.env.RAG_EVAL_BASE_URL||'http://127.0.0.1:8080'});
    if(!args.length) {console.log(JSON.stringify(audit,null,2));return;}
    const bytes=Buffer.from(JSON.stringify(audit,null,2)+'\n');const sha256=createHash('sha256').update(bytes).digest('hex');
    const target=writeSealedCatalogue({bytes,sha256},path.resolve(__dirname,'../docs/evidence/rag-quality/evaluation-versions/source-audits'));
    console.log(JSON.stringify({sha256,path:target,sourceOwnerCount:audit.sourceOwners.length,registered:false,modelCalls:0},null,2));
  })().catch(error=>{console.error('Read-only source audit failed: '+error.message);process.exitCode=1;});
}
module.exports={captureVersionedSourceAudit,sourceAuditToolHash,readRuntimeInfo};
