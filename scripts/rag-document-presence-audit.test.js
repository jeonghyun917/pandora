const test = require('node:test');
const assert = require('node:assert/strict');

const {
  canonicalTitle,
  classifyTitleMatch,
  qdrantDocumentFilter,
} = require('./rag-document-presence-audit');

test('expands the maintained short title for the national contract enforcement decree', () => {
  assert.equal(
    canonicalTitle('국가계약법 시행령'),
    canonicalTitle('국가를 당사자로 하는 계약에 관한 법률 시행령'),
  );
});

test('treats an administrative-rule decoration as a canonical title match', () => {
  assert.equal(
    classifyTitleMatch('용역계약일반조건', '(계약예규) 용역계약일반조건'),
    'canonical',
  );
});

test('keeps exact and canonical title matches distinguishable', () => {
  assert.equal(
    classifyTitleMatch(
      '국가를 당사자로 하는 계약에 관한 법률 시행령',
      '국가를 당사자로 하는 계약에 관한 법률 시행령',
    ),
    'exact',
  );
});

test('does not collapse a longer, different document into the same identity', () => {
  assert.equal(
    classifyTitleMatch('용역계약일반조건', '용역계약일반조건 처리지침'),
    'text',
  );
  assert.equal(
    classifyTitleMatch('용역계약일반조건', '물품구매계약 일반조건'),
    'none',
  );
});

test('scopes Qdrant presence to one version document id as well as title', () => {
  assert.deepEqual(
    qdrantDocumentFilter('국가를 당사자로 하는 계약에 관한 법률 시행령', 50664),
    {
      must: [
        {
          key: 'title',
          match: { value: '국가를 당사자로 하는 계약에 관한 법률 시행령' },
        },
        {
          key: 'documentId',
          match: { value: 50664 },
        },
      ],
    },
  );
});
