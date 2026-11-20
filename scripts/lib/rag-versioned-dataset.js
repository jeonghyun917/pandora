// Pure inactive payload preparation. No endpoint, slot, write or release activation.
const {isDeepStrictEqual}=require('node:util');
const {RELEASE_EVALUATION_CASE_COUNT}=require('./rag-eval-provenance');
const hash=value=>typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
const listFields=new Set(['targets','expectedTerms','expectedTitleTerms','expectedSectionTypes','forbiddenTerms',
  'expectedDocumentTerms','expectedPageNumbers','expectedParentTerms','expectedResultMsgs','expectedAnswerTerms','forbiddenAnswerTerms']);
const groupFields=new Set(['requiredPropositionGroups','requiredConditionGroups']);
const stringFields=new Set(['question','answerDirection','failureTaxonomy']);
const strings=value=>Array.isArray(value) && value.every(x=>typeof x==='string' && x.trim().length>0);
function validPatchField(key,value) {
  if(stringFields.has(key)) return typeof value==='string' && value.trim().length>0;
  if(key==='requiredMatches') return Number.isSafeInteger(value) && value>=0;
  if(key==='answerVerificationRequired') return typeof value==='boolean';
  if(groupFields.has(key)) return Array.isArray(value) && value.every(group=>strings(group) && group.length>0);
  if(!listFields.has(key) || !strings(value)) return false;
  return key!=='targets' || value.length>0 && value.every(x=>['law','admrul','official_doc','internal_doc'].includes(x));
}
function applyDeclaredCaseVersion(baseline,baselineSha256,descriptor) {
  if(!hash(baselineSha256) || !hash(descriptor?.baselineDatasetSha256)
      || descriptor.baselineDatasetSha256!==baselineSha256) throw Error('Frozen baseline SHA-256 mismatch');
  if(!Array.isArray(baseline) || baseline.length!==RELEASE_EVALUATION_CASE_COUNT)
    throw Error('Expected the complete1003-case baseline');
  const ids=baseline.map(row=>row?.id);
  if(ids.some(id=>typeof id!=='string' || !id.trim()) || new Set(ids).size!==ids.length)
    throw Error('Baseline requires positive unique case identities');
  if(descriptor.schemaVersion!==1 || descriptor.status!=='inactive-proposed-payload'
      || typeof descriptor.version!=='string' || !/^[a-z0-9][a-z0-9._-]{2,100}$/.test(descriptor.version)
      || !Array.isArray(descriptor.overrides) || descriptor.overrides.length===0)
    throw Error('Expected an explicit inactive version descriptor');
  const result=structuredClone(baseline),positions=new Map(ids.map((id,index)=>[id,index])),seen=new Set();
  for(const override of descriptor.overrides) {
    if(!override || !positions.has(override.id)) throw Error('Unknown override case ID');
    if(seen.has(override.id)) throw Error('Duplicate override case ID');
    seen.add(override.id);
    const patch=override.patch;
    if(!patch || typeof patch!=='object' || Array.isArray(patch) || Object.keys(patch).length===0
        || Object.entries(patch).some(([key,value])=>!validPatchField(key,value))) throw Error('Unsupported case patch');
    const index=positions.get(override.id),updated={...result[index],...structuredClone(patch)};
    if(isDeepStrictEqual(updated,result[index])) throw Error('No-op case patch');
    result[index]=updated;
  }
  return result;
}
module.exports={applyDeclaredCaseVersion};
