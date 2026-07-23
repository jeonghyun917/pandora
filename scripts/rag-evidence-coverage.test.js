const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const {
  GRAMMATICAL_NEGATIONS,
  LOCAL_POLARITY_BRIDGES,
  matchOracleGroup,
  parseJavaListConstant,
} = require('./lib/rag-explicit-oracle-matcher');

function canonicalJavaMarkers(name) {
  const source = fs.readFileSync(
    `${__dirname}/../src/main/java/com/kaces/pandora/ai/answer/ExplicitOracleTermMatcher.java`,
    'utf8',
  );
  return parseJavaListConstant(source, name);
}

function assertMarkerParity(name, source, target) {
  const missing = source.filter((marker) => !target.includes(marker));
  const extra = target.filter((marker) => !source.includes(marker));
  if (source.length !== target.length || missing.length > 0 || extra.length > 0) {
    throw new Error(
      `${name} marker parity mismatch: sourceCount=${source.length} targetCount=${target.length} `
      + `missing=${JSON.stringify(missing)} extra=${JSON.stringify(extra)}`,
    );
  }
}

test('matches one OR alias within an AND group', () => {
  const aliases = ['등록 요청을 받는 경우', '등록 요청이 있으면'];

  assert.equal(matchOracleGroup('등록 요청이 있으면 처리해야 합니다.', aliases), aliases[1]);
});

test('requires every material token in one text', () => {
  const alias = '개인정보 처리 목적 보유 기간';

  assert.equal(
    matchOracleGroup('개인정보는 처리 목적에 따라 보유 기간을 정합니다.', [alias]),
    alias,
  );
});

test('does not synthesize an alias from separate items or sentences', () => {
  const alias = '개인정보 처리 목적 보유 기간';
  const items = ['개인정보 처리 목적입니다.', '보유 기간을 정합니다.'];

  assert.equal(items.some((item) => matchOracleGroup(item, [alias]) !== null), false);
  assert.equal(matchOracleGroup(items.join(' '), [alias]), null);
});

test('does not match opposite positive and negative local polarity', () => {
  const positive = '개인정보를 처리할 수 있습니다';
  const negative = '개인정보를 처리할 수 없습니다';

  assert.equal(matchOracleGroup(negative, [positive]), null);
  assert.equal(matchOracleGroup(positive, [negative]), null);
});

test('normalizes Korean punctuation and spacing before matching', () => {
  const alias = '개인정보 처리 목적에 따른 보유 기간';

  assert.equal(
    matchOracleGroup('개인정보 처리목적에 따른, 보유기간입니다.', [alias]),
    alias,
  );
});

test('preserves dotted dates while splitting sentences like AnswerOracleMatcher', () => {
  const alias = '2025. 12. 17 ~ 2026. 10. 31';

  assert.equal(
    matchOracleGroup('IRM 측정기간은 2025. 12. 17 ~ 2026. 10. 31입니다.', [alias]),
    alias,
  );
});

test('does not match a positive alias before grammatical 안', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치해서는 안 됩니다.', [alias]), null);
});

test('does not match a positive alias through a conditional bridge before 안', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치하면 안 됩니다.', [alias]), null);
});

test('does not match a proposition superseded by a contrast final assertion', () => {
  const alias = '비대상';

  assert.equal(
    matchOracleGroup('비대상이라는 견해도 있지만 실제로는 과업심의 대상입니다.', [alias]),
    null,
  );
});

test('does not match a positive alias through a 할 경우 bridge before 안 됨', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치할 경우 안 됨.', [alias]), null);
});

test('does not match a positive alias through a 했을 경우 bridge before 안 됩니다', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치했을 경우 안 됩니다.', [alias]), null);
});

test('does not match a positive alias before direct 안 됨', () => {
  const alias = '공개장소에 자유롭게 설치';

  assert.equal(matchOracleGroup('공개장소에 자유롭게 설치 안 됨.', [alias]), null);
});

test('does not match a proposition superseded by 실제로 without 는', () => {
  const alias = '비대상';

  assert.equal(
    matchOracleGroup('비대상이라는 견해도 있지만 실제로 과업심의 대상입니다.', [alias]),
    null,
  );
});

test('does not match a proposition superseded by 오히려', () => {
  const alias = '비대상';

  assert.equal(
    matchOracleGroup('비대상이라는 견해도 있지만 오히려 과업심의 대상입니다.', [alias]),
    null,
  );
});

test('does not match through every Java grammatical-negation marker', () => {
  const alias = '공개장소에 자유롭게 설치';
  assert.equal(typeof parseJavaListConstant, 'function');

  for (const marker of canonicalJavaMarkers('GRAMMATICAL_NEGATIONS')) {
    assert.equal(matchOracleGroup(`${alias}${marker}.`, [alias]), null, marker);
  }
  assert.equal(matchOracleGroup(`${alias}아닙니다.`, [alias]), null, '아닙니다');
});

test('does not match through every Java local-polarity bridge', () => {
  const alias = '공개장소에 자유롭게 설치';
  assert.equal(typeof parseJavaListConstant, 'function');

  for (const bridge of canonicalJavaMarkers('LOCAL_POLARITY_BRIDGES')) {
    assert.equal(matchOracleGroup(`${alias}${bridge}안됨.`, [alias]), null, bridge);
  }
});

test('parses Java string escapes in marker constants', () => {
  assert.equal(typeof parseJavaListConstant, 'function');
  assert.deepEqual(
    parseJavaListConstant('private static final List<String> EXAMPLE = List.of("a\\n", "\\uAC00");', 'EXAMPLE'),
    ['a\n', '가'],
  );
});

test('reports explicit marker differences for a mismatched Node fixture', () => {
  assert.throws(
    () => assertMarkerParity('fixture', ['canonical'], ['node']),
    /fixture marker parity mismatch:.*missing=\["canonical"\].*extra=\["node"\]/,
  );
});

test('ports the complete canonical Java marker tables without set differences', () => {
  assert.equal(typeof parseJavaListConstant, 'function');
  assertMarkerParity(
    'GRAMMATICAL_NEGATIONS',
    canonicalJavaMarkers('GRAMMATICAL_NEGATIONS'),
    GRAMMATICAL_NEGATIONS,
  );
  assertMarkerParity(
    'LOCAL_POLARITY_BRIDGES',
    canonicalJavaMarkers('LOCAL_POLARITY_BRIDGES'),
    LOCAL_POLARITY_BRIDGES,
  );
});
