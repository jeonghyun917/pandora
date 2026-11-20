const path=require('node:path'),{createHash}=require('node:crypto');
const {loadVersionedCatalogue}=require('./lib/rag-versioned-evaluation');
const {prepareApprovalRequest,prepareExecutionManifest}=require('./lib/rag-versioned-artifacts');
const {readRuntimeInfo}=require('./capture-versioned-source-audit');
const {writeSealedCatalogue}=require('./seal-rag-evaluation-catalogue');
const {readBoundJson}=require('./lib/rag-versioned-authorization');
async function main(args=process.argv.slice(2)) {
  const write=args.at(-1)==='--write';if(write)args=args.slice(0,-1);
  const [mode,first,second]=args;
  if(!['approval-request','execution-manifest'].includes(mode) || args.length!==(mode==='approval-request'?2:3))
    throw Error('Expected approval-request SOURCE_AUDIT_REL or execution-manifest REQUEST_REL RECEIPT_REL, optional --write');
  const evaluationContractSha256=process.env.RAG_EVAL_CONTRACT_SHA256;
  const {catalogue}=loadVersionedCatalogue({evaluationContractSha256});
  if(mode==='execution-manifest') {
    const root=path.resolve(__dirname,'..'),request=readBoundJson(root,first);
    const audit=readBoundJson(root,request.value.sourceAuditReference);
    prepareExecutionManifest({root,catalogue,evaluationContractSha256,approvalRequestPath:first,
      approvalReceiptPath:second,currentRuntimeInfo:audit.value.runtimeInfo});
  }
  const value=mode==='approval-request'?prepareApprovalRequest({catalogue,evaluationContractSha256,sourceAuditPath:first})
    :prepareExecutionManifest({catalogue,evaluationContractSha256,approvalRequestPath:first,approvalReceiptPath:second,
      currentRuntimeInfo:await readRuntimeInfo({baseUrl:process.env.RAG_EVAL_BASE_URL||'http://127.0.0.1:8080'})});
  const bytes=Buffer.from(JSON.stringify(value,null,2)+'\n'),sha256=createHash('sha256').update(bytes).digest('hex');
  if(!write){console.log(bytes.toString());return;}
  const target=writeSealedCatalogue({bytes,sha256},path.resolve(__dirname,'../docs/evidence/rag-quality/evaluation-versions',mode+'s'));
  console.log(JSON.stringify({sha256,path:target,modelCalls:0,registrationChanged:false,receiptCreated:false},null,2));
}
if(require.main===module)main().catch(error=>{console.error(error.message);process.exitCode=1;});
module.exports={main};
