const {test}=require('node:test');
const assert=require('node:assert/strict');
const {scoreReferenceCommencement:score}=require('./lib/rag-reference-commencement-scorer');
const owner={target:'law',documentId:42,chunkId:99};
const contract={title:'예시행정법',lawId:'013792',edition:'283735',chunkVersion:2,effectiveFrom:'2026-08-28',asOf:'2026-11-18',owner};
function report() {
  const facts=['예시행정법 시행일은 2026년 8월 28일이다.','예시행정법의 일반 시행 상태는 2026년 11월 18일 기준 시행 중이다.'];
  const links=facts.map(claim=>({claim,evidenceSentence:claim,relation:'SUPPORTED',groundNumber:1}));
  return {verifiedAnswer:facts.join('\n'),aligned:true,insufficientEvidence:false,
    unsafeSemanticShadowDisagreementCount:0,unsupportedAnswerClaims:[],unsupportedNumericClaims:[],contradictedAnswerClaims:[],
    claimEvidenceLinks:links,attributedClaims:links.map(link=>({...owner,link})),
    referencePhase:{lawId:'013792',edition:'283735',documentId:42,chunkId:99,chunkVersion:2,
      effectiveFrom:'2026-08-28',effectiveUntilExclusive:'2027-02-28',referenceDate:'2026-11-18',observedAt:'2026-11-20',sourceSha256:'a'.repeat(64),
      commencement:{effectiveDate:'2026-08-28',sourcePath:'$.법령.부칙.부칙단위[0]',
        clause:'제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다. 다만, 제32조는 공포 후 1년이 경과한 날부터 시행한다.'}}};
}
test('requires both complete supported facts and a same-owner official reference receipt',()=>{
  assert.equal(score(report(),contract),true);
});
test('rejects the wrong date even when the reference year appears elsewhere',()=>{
  const r=report();
  r.claimEvidenceLinks[0].claim=r.claimEvidenceLinks[0].claim.replace('2026년','2025년');
  r.claimEvidenceLinks[0].evidenceSentence=r.claimEvidenceLinks[0].claim;
  r.verifiedAnswer=r.claimEvidenceLinks.map(x=>x.claim).join('\n');
  assert.equal(score(r,contract),false);
});
test('rejects missing receipt, another owner or source version and out-of-interval dates',()=>{
  for(const change of [{lawId:'013793'},{edition:'251025'},{documentId:43},{chunkId:100},{chunkVersion:1},
    {effectiveFrom:'2026-08-27'},{referenceDate:'2026-11-17'},{effectiveUntilExclusive:'2026-11-18'},
    {observedAt:'2026-11-17'},{effectiveUntilExclusive:'2027-02-30'},{sourceSha256:''}]) {
    const r=report();Object.assign(r.referencePhase,change);assert.equal(score(r,contract),false,JSON.stringify(change));
  }
  const r=report();delete r.referencePhase;assert.equal(score(r,contract),false);
});
test('rejects partial, ambiguous or widened answers and failed verification',()=>{
  const mutations=[r=>r.claimEvidenceLinks.pop(),r=>r.attributedClaims[0].chunkId=100,
    r=>r.attributedClaims.push(r.attributedClaims[0]),r=>r.verifiedAnswer+=' 모든 조문이 시행 중이다.',
    r=>r.aligned=false,r=>r.insufficientEvidence=true,r=>r.unsupportedNumericClaims.push('wrong date'),
    r=>r.unsafeSemanticShadowDisagreementCount=1,r=>r.claimEvidenceLinks[1].groundNumber=2,
    r=>r.referencePhase.commencement.clause='',r=>r.referencePhase.commencement.sourcePath='$.법령.조문[0]'];
  for(const mutate of mutations) {const r=report();mutate(r);assert.equal(score(r,contract),false);}
  assert.equal(score(null,contract),false);assert.equal(score(report(),null),false);
});
