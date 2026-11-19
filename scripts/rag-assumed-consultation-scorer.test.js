const { test } = require('node:test');
const assert = require('node:assert/strict');
const score = require('./lib/rag-qualified-example-scorer').scoreAssumedConsultation;
const scope = { owner: { target: 'admrul', documentId: 22614, chunkId: 11138987 },
  clauses: ['대상사업 조건.', '제외 조건.', '신규 정보화 단서.'] };
const remedy = { owner: { ...scope.owner, chunkId: 11138991 }, literal: '협의기관의 장은 신청기관에 시정을 요구할 수 있다.' };
const contract = { scope, remedy };
function fixture() {
  const claims = [...scope.clauses, remedy.literal];
  const links = claims.map((claim, i) => ({ claim, evidenceSentence: claim, groundNumber: i < 3 ? 1 : 2, relation: 'SUPPORTED' }));
  return { aligned: true, insufficientEvidence: false, unsafeSemanticShadowDisagreementCount: 0,
    unsupportedAnswerClaims: [], unsupportedNumericClaims: [], contradictedAnswerClaims: [],
    verifiedAnswer: claims.join('\n'), claimEvidenceLinks: links,
    attributedClaims: links.map((link, i) => ({ ...i < 3 ? scope.owner : remedy.owner, link })) };
}
test('requires all three owned scope spans and the separately owned remedy', () => {
  assert.equal(score(fixture(), contract), true);
});
test('rejects missing proviso, different document, promoted scope, extra claims and unsupported envelope', () => {
  for (const mutate of [
    r => { r.claimEvidenceLinks.splice(2,1); r.attributedClaims.splice(2,1); r.verifiedAnswer=r.verifiedAnswer.replace(scope.clauses[2]+'\n',''); },
    r => { r.attributedClaims[2].documentId++; },
    r => { r.attributedClaims[3].chunkId = scope.owner.chunkId; },
    r => { r.claimEvidenceLinks[3].groundNumber = 1; },
    r => { r.verifiedAnswer += '\n모든 사업에 제재한다.'; },
    r => { r.unsupportedAnswerClaims.push('근거 없음'); },
  ]) {
    const r=fixture(); mutate(r); assert.equal(score(r,contract),false);
  }
});

test('malformed evidence is rejected instead of throwing or accepting partial proof', () => {
  for (const mutate of [
    r => { r.claimEvidenceLinks[0] = null; },
    r => { r.claimEvidenceLinks[3] = null; },
    r => { r.attributedClaims[0] = null; },
    r => { r.claimEvidenceLinks[1].groundNumber = 3; },
    r => { r.claimEvidenceLinks.reverse(); r.attributedClaims.reverse(); },
    r => { r.aligned = false; },
    r => { r.unsafeSemanticShadowDisagreementCount = 1; },
  ]) {
    const r=fixture(); mutate(r); assert.equal(score(r,contract),false);
  }
  assert.equal(score(null,contract),false);
});
