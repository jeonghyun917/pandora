const { test } = require('node:test');
const assert = require('node:assert/strict');
const legacy = require('./lib/rag-qualified-example-scorer');
const { scoreQualifiedConditionalExample: score } = require('./lib/rag-qualified-example-scorer');

const qualification = '이 지침은 국가기관등이 법 제2조에 따른 소프트웨어사업을 수행할 경우에 적용한다.';
const remedy = '국가기관등의 장은 하도급 승인 심사 중 하도급 제한 위반 시 서류 보완을 요청할 수 있다.';
const owner = { target: 'admrul', documentId: 18109, chunkId: 11294138 };
const contract = {
  remedy: { owner, anchors: { actor: '국가기관등의 장은', projectScope: '하도급 승인 심사 중',
    trigger: '하도급 제한 위반 시', action: '서류 보완을 요청', modality: '요청할 수 있다' } },
  qualification: { owner: { ...owner, chunkId: 11294119 },
    literal: qualification, heading: '제2조(적용범위)' },
};
const fixture = () => {
  const link = (claim, evidenceSentence, groundNumber) => ({ claim, evidenceSentence, groundNumber, relation: 'SUPPORTED' });
  const scope = link(qualification, '제2조(적용범위) ' + qualification, 1);
  const action = link(remedy, remedy, 2);
  return { aligned: true, insufficientEvidence: false, unsafeSemanticShadowDisagreementCount: 0,
    unsupportedAnswerClaims: [], unsupportedNumericClaims: [], contradictedAnswerClaims: [],
    verifiedAnswer: qualification + '\n' + remedy, claimEvidenceLinks: [scope, action],
    attributedClaims: [{ ...contract.qualification.owner, link: scope }, { ...owner, link: action }] };
};

test('requires the source-owned applicability statement and the distinct operative remedy', () => {
  assert.equal(score(fixture(), contract), true);
  assert.equal(legacy.scoreConditionalExamples(fixture(), [contract.remedy]), false);
});

test('rejects omitted, misattributed, broadened, duplicate, or unsafe evidence', () => {
  const mutations = [
    r => { r.claimEvidenceLinks.shift(); r.attributedClaims.shift(); r.verifiedAnswer = remedy; },
    r => { r.attributedClaims[0].documentId++; },
    r => { r.attributedClaims[0].chunkId = owner.chunkId; },
    r => { r.claimEvidenceLinks[0].evidenceSentence += ' 모든 공공사업에 적용한다.'; },
    r => { r.claimEvidenceLinks[0].groundNumber = 2; },
    r => { r.verifiedAnswer += ' 자동 제재한다.'; },
    r => { r.unsupportedAnswerClaims.push('추가 제재'); },
    r => { r.unsafeSemanticShadowDisagreementCount = 1; },
  ];
  for (const mutate of mutations) {
    const r = fixture();
    mutate(r);
    assert.equal(score(r, contract), false, String(mutate));
  }
});
