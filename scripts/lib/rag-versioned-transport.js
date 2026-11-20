const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
function postVersionedEvaluation(endpoint,{payload,evaluationContractSha256,signal,fetchImpl=fetch}) {
  return fetchImpl(endpoint,{method:'POST',headers:{'Content-Type':'application/json; charset=utf-8',
    'X-Pandora-Evaluation-Contract-SHA256':evaluationContractSha256},body:JSON.stringify(payload),signal,redirect:'error'});
}
function recordVersionedAttempt(checkpointPath,attempt,evidence) {
  assert.ok(Number.isSafeInteger(attempt) && attempt>0,'Positive attempt sequence required');
  const target=path.join(path.dirname(checkpointPath),path.basename(checkpointPath,'.json')+`-attempt-${String(attempt).padStart(4,'0')}.json`);
  fs.writeFileSync(target,JSON.stringify({schemaVersion:1,observedAt:new Date().toISOString(),promotionEligible:false,...evidence},null,2)+'\n',
    {encoding:'utf8',flag:'wx',flush:true});
  return target;
}
module.exports={postVersionedEvaluation,recordVersionedAttempt};
