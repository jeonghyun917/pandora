// Offline candidate only; this does not activate a contract or rescore legacy runs.
const {uniquelyAttributed}=require('./rag-qualified-example-scorer');
const fold=value=>typeof value==='string'?value.replace(/\s+/gu,' ').trim():'';
function validDate(value) {
  if(typeof value!=='string' || !/^[12][0-9]{3}-[0-9]{2}-[0-9]{2}$/.test(value))return false;
  const date=new Date(value+'T00:00:00Z');
  return Number.isFinite(date.valueOf()) && date.toISOString().slice(0,10)===value;
}
const koreanDate=value=>{const [year,month,day]=value.split('-').map(Number);return `${year}년 ${month}월 ${day}일`;};
function scoreReferenceCommencement(report,contract) {
  const phase=report?.referencePhase;
  if(!contract || !phase || !fold(contract.title) || contract.owner?.target!=='law'
      || !Number.isSafeInteger(contract.chunkVersion) || contract.chunkVersion<=0
      || ![contract.effectiveFrom,contract.asOf,phase.effectiveUntilExclusive,phase.observedAt].every(validDate)
      || !['lawId','edition'].every(key=>typeof contract[key]==='string' && /^[0-9]+$/.test(contract[key]) && phase[key]===contract[key])
      || !['documentId','chunkId'].every(key=>Number.isSafeInteger(contract.owner[key]) && contract.owner[key]>0 && phase[key]===contract.owner[key])
      || phase.chunkVersion!==contract.chunkVersion || phase.effectiveFrom!==contract.effectiveFrom || phase.referenceDate!==contract.asOf
      || contract.asOf<phase.effectiveFrom || contract.asOf>=phase.effectiveUntilExclusive || contract.asOf>phase.observedAt
      || typeof phase.sourceSha256!=='string' || !/^[a-f0-9]{64}$/.test(phase.sourceSha256)
      || phase.commencement?.effectiveDate!==contract.effectiveFrom
      || !/^\$\.법령\.부칙\.부칙단위\[[0-9]+\]$/u.test(phase.commencement?.sourcePath??'')
      || !/^제[0-9]+조\s*\(시행일\)\s*\S/u.test(fold(phase.commencement?.clause))
      || report.aligned!==true || report.insufficientEvidence!==false || report.unsafeSemanticShadowDisagreementCount!==0
      || !['unsupportedAnswerClaims','unsupportedNumericClaims','contradictedAnswerClaims'].every(key=>Array.isArray(report[key]) && report[key].length===0)
      || !Array.isArray(report.claimEvidenceLinks) || report.claimEvidenceLinks.length!==2
      || !Array.isArray(report.attributedClaims) || report.attributedClaims.length!==2) return false;
  const expected=[`${contract.title} 시행일은 ${koreanDate(contract.effectiveFrom)}이다.`,
    `${contract.title}의 일반 시행 상태는 ${koreanDate(contract.asOf)} 기준 시행 중이다.`];
  const links=report.claimEvidenceLinks;
  return links.every(link=>link && typeof link==='object')
    && links[0].groundNumber===links[1].groundNumber
    && fold(report.verifiedAnswer)===expected.join(' ')
    && links.every((link,index)=>fold(link.claim)===expected[index] && fold(link.evidenceSentence)===expected[index]
      && uniquelyAttributed(report,link.claim,contract.owner));
}
module.exports={scoreReferenceCommencement};
