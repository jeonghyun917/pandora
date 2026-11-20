const HEADER='X-Pandora-Evaluation-Contract-SHA256';
const canonical=value=>typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
// Read-only availability boundary. Reserve a one-shot slot only AFTER this and
// the unchanged runtime/corpus/payload preflights. This function sends no cases.
async function preflightVersionedEvaluationContract({baseUrl,evaluationContractSha256,fetchImpl=fetch,timeoutMs=5000}={}) {
  if(!canonical(evaluationContractSha256))throw Error('Expected canonical evaluation contract SHA-256');
  const base=new URL(baseUrl);
  if(!['http:','https:'].includes(base.protocol) || base.username || base.password || base.pathname!=='/' || base.search || base.hash)
    throw Error('Expected a credential-free HTTP server origin');
  if(!Number.isSafeInteger(timeoutMs) || timeoutMs<1 || timeoutMs>30000)throw Error('Invalid preflight timeout');
  const response=await fetchImpl(new URL('/api/law-data/ai/debug/evaluation-contracts',base),{
    method:'GET',headers:{Accept:'application/json'},redirect:'error',signal:AbortSignal.timeout(timeoutMs),
  });
  if(response.status!==200)throw Error(`Evaluation contract capabilities unavailable: HTTP ${response.status}`);
  if(!response.headers.get('content-type')?.toLowerCase().startsWith('application/json'))
    throw Error('Expected JSON contract capabilities');
  if(Number(response.headers.get('content-length'))>65536)throw Error('Oversized contract capabilities');
  const text=await response.text();
  if(text.length>65536)throw Error('Oversized contract capabilities');
  const descriptor=JSON.parse(text);
  if(descriptor?.schemaVersion!==1 || descriptor.contractHeader!==HEADER || !Array.isArray(descriptor.registeredContracts)
      || descriptor.registeredContracts.some(hash=>!canonical(hash))
      || new Set(descriptor.registeredContracts).size!==descriptor.registeredContracts.length)
    throw Error('Malformed contract capabilities');
  if(!descriptor.registeredContracts.includes(evaluationContractSha256))throw Error('Evaluation contract is not registered');
  return {schemaVersion:1,baseUrl:base.origin,evaluationContractSha256,contractHeader:HEADER};
}
module.exports={preflightVersionedEvaluationContract};
