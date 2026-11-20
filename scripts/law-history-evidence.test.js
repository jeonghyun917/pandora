const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { proveGeneralEffectivePhase: prove } = require('./lib/law-history-evidence');
const historyEvidence = require('./lib/law-history-evidence');
const source = fs.readFileSync(path.join(__dirname,'../docs/evidence/rag-quality/law-013792-history-20261120.html'),'utf8');
const request = { lawId:'013792', edition:'283735', promulgation:'20260227', number:'21392',
  effectiveFrom:'20260828', asOf:'20261118', observedAt:'20261120', scope:'general-phase',
  sourceUrl:'https://www.law.go.kr/LSW/lsHstListR.do?lsiSeq=283735&lsId=013792&ancYnChk=0' };

test('binds a reference date to a bounded official effective phase, not to every provision', () => {
  const proof = prove(source, request);
  assert.equal(proof?.effectiveFrom,'20260828');
  assert.equal(proof?.effectiveUntilExclusive,'20270228');
  assert.equal(proof?.asOf,'20261118');
  assert.equal(proof?.scope,'general-phase');
  assert.match(proof?.sourceSha256 ?? '',/^[a-f0-9]{64}$/);
});

test('rejects a different edition, boundary, scope, unbounded history and future observation', () => {
  for (const mutation of [
    { edition:'251025' }, { number:'21393' }, { effectiveFrom:'20270228' },
    { asOf:'20260827' }, { asOf:'20270228' }, { scope:'all-provisions' }, { scope:'제32조' },
    { observedAt:'20261117' }, { asOf:'20260230' },
    { sourceUrl:'https://example.com/LSW/lsHstListR.do?lsiSeq=283735&lsId=013792&ancYnChk=0' },
    { lawId:'013793' },
  ]) assert.equal(prove(source,{...request,...mutation}),null,JSON.stringify(mutation));
  assert.equal(prove(source.replaceAll('20270228','20260828'),request),null);
  assert.equal(prove(source.replace("'20270228'","'unknown-date'"),request),null);
  assert.equal(prove(source.slice(0,source.indexOf('</html>')),request),null);
  assert.equal(prove(source,{...request,sourceUrl:request.sourceUrl+'&lsId=013793'}),null);
  assert.equal(prove(source.replace(/<li[^>]*>[\s\S]*?20270228[\s\S]*?<\/li>/,''),request),null);
});

const clause = '제1조(시행일) 이 법은 공포 후 6개월이 경과한 날부터 시행한다. 다만, 제32조는 공포 후 1년이 경과한 날부터 시행한다.';
const rawJson = JSON.stringify({법령:{기본정보:{법령ID:'013792',공포번호:'21392',공포일자:'20260227',시행일자:'20260828'},
  부칙:{부칙단위:[{부칙공포번호:'21392',부칙공포일자:'20260227',부칙내용:[['부칙',clause]]}]}}});
const sourceRow = {target:'law',documentId:42,chunkId:99,chunkVersion:2,edition:'283735',rawJson};
const receipt = {target:'law',documentId:42,chunkId:99,chunkVersion:2,edition:'283735',
  sourcePath:'$.법령.부칙.부칙단위[0]',clause,effectiveFrom:'20260828',
  sourceSha256:require('node:crypto').createHash('sha256').update(rawJson).digest('hex')};

test('binds history to the same stored promulgation and the complete commencement clause', () => {
  assert.equal(typeof historyEvidence.bindGeneralPhaseToCommencement,'function');
  const proof = historyEvidence.bindGeneralPhaseToCommencement(source,request,sourceRow,receipt);
  assert.equal(proof?.commencement.clause,clause);
  assert.equal(proof?.phase.asOf,'20261118');
  assert.equal(proof?.phase.effectiveUntilExclusive,'20270228');
  assert.equal(proof?.owner.chunkVersion,2);
  assert.equal(proof?.scope,'general-phase');
  assert.match(proof?.evidenceSha256 ?? '',/^[a-f0-9]{64}$/);
});

test('cannot combine another edition, receipt, truncated exception or provision scope', () => {
  assert.equal(typeof historyEvidence.bindGeneralPhaseToCommencement,'function');
  const bind = (row=sourceRow,proof=receipt,query=request) =>
    historyEvidence.bindGeneralPhaseToCommencement(source,query,row,proof);
  for (const change of [{edition:'251025'},{target:'admrul'},{documentId:43},{chunkId:100},{chunkVersion:1},
    {sourcePath:'$.법령.부칙.부칙단위[1]'},{clause:clause.split(' 다만,')[0]},
    {effectiveFrom:'20270228'},{sourceSha256:'0'.repeat(64)}])
    assert.equal(bind(sourceRow,{...receipt,...change}),null,JSON.stringify(change));
  for (const change of [{edition:'251025'},{rawJson:'{'},{chunkVersion:0},{documentId:0},
    {rawJson:rawJson.replace('013792','013793')}, {rawJson:rawJson.replaceAll('21392','21393')},
    {rawJson:rawJson.replaceAll('20260227','20260226')}, {rawJson:rawJson.replace('20260828','20270228')}])
    assert.equal(bind({...sourceRow,...change}),null,JSON.stringify(change));
  assert.equal(bind(sourceRow,receipt,{...request,scope:'all-provisions'}),null);
  assert.equal(bind(sourceRow,receipt,{...request,scope:'제32조'}),null);
  const duplicate = JSON.parse(rawJson);
  duplicate.법령.부칙.부칙단위.push(duplicate.법령.부칙.부칙단위[0]);
  assert.equal(bind({...sourceRow,rawJson:JSON.stringify(duplicate)}),null);
  assert.equal(bind(null,receipt),null);
  assert.equal(bind(sourceRow,null),null);
});
