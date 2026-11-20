const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { proveGeneralEffectivePhase: prove } = require('./lib/law-history-evidence');
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
