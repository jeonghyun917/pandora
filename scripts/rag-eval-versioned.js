// Dedicated entry: omission must never run the legacy frozen full evaluation.
(async()=>{
  for(const key of ['RAG_EVAL_CONTRACT_SHA256','RAG_EVAL_STAGE','RAG_EVAL_EXECUTION_MANIFEST','RAG_EVAL_PAYLOAD_APPROVAL'])
    if(!process.env[key])throw Error(`${key} is required`);
  if(!/^[a-f0-9]{64}$/.test(process.env.RAG_EVAL_CONTRACT_SHA256))throw Error('RAG_EVAL_CONTRACT_SHA256 must be canonical');
  await require('./rag-eval-gate').main({requireVersioned:true});
})().catch(error=>{console.error('[rag-eval-versioned] ERROR '+error.message);process.exitCode=1;});
