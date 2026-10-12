import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

test('activation invokes only the explicit version saga and never broad rebuild', async () => {
  assert.equal(typeof api.activateDocumentCandidate, 'function');
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/api/law-data/chunks/activate-candidate');
      assert.equal(options.body.toString(), 'documentId=123&candidateVersion=2');
      return new Response('{"activated":true}');
    };
    await assert.rejects(api.activateDocumentCandidate('1,2', '2'));
    await assert.rejects(api.activateDocumentCandidate('123', '0'));
    assert.deepEqual(await api.activateDocumentCandidate('123', '2'), { activated: true });
  } finally { globalThis.fetch = original; }
});

test('resumed indexing sends only an explicit document version and bounded limit', async () => {
  assert.equal(typeof api.resumeCandidateIndex, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  try {
    globalThis.fetch = async (url, options) => {
      calls++;
      assert.equal(url, '/api/law-data/semantic/index-candidate');
      assert.equal(options.body.toString(), 'target=admrul&documentId=123&candidateVersion=2&limit=52');
      return new Response('{"indexed":52}');
    };
    await assert.rejects(api.resumeCandidateIndex('ALL', '123', '2', '52'));
    await assert.rejects(api.resumeCandidateIndex('law', '1,2', '2', '52'));
    await assert.rejects(api.resumeCandidateIndex('law', '123', '0', '52'));
    assert.equal(calls, 0);
    await api.resumeCandidateIndex('admrul', '123', '2', '52');
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

test('candidate indexing is scoped to the returned nonactive approved version', async () => {
  assert.equal(typeof api.indexDocumentCandidate, 'function');
  const original = globalThis.fetch;
  const candidate = { documentId: 123, chunkVersion: 2, activationStatus: 'CANDIDATE', expectedChunkCount: 52, previewApproved: true, unexplainedLossSpanCount: 0 };
  let calls = 0;
  try {
    globalThis.fetch = async (url, options) => {
      calls++;
      assert.equal(url, '/api/law-data/semantic/index-candidate');
      assert.equal(options.body.get('documentId'), '123');
      assert.equal(options.body.get('candidateVersion'), '2');
      assert.equal(options.body.get('limit'), '52');
      return new Response('{"indexed":52}');
    };
    await assert.rejects(api.indexDocumentCandidate('ALL', candidate));
    await assert.rejects(api.indexDocumentCandidate('admrul', { ...candidate, activationStatus: 'ACTIVE' }));
    await assert.rejects(api.indexDocumentCandidate('admrul', { ...candidate, previewApproved: false }));
    assert.equal(calls, 0);
    assert.deepEqual(await api.indexDocumentCandidate('admrul', candidate), { indexed: 52 });
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

test('candidate creation requires a matching zero-loss single-document server preview', async () => {
  assert.equal(typeof api.createDocumentCandidate, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  const preview = { target: 'admrul', documents: 1, items: [{ documentId: 123, target: 'admrul', projectedChunks: 52, unexplainedLossSpanCount: 0, approvalToken: 'server-value' }] };
  try {
    globalThis.fetch = async (url, options) => {
      calls++;
      assert.equal(url, '/api/law-data/chunks/create-candidate');
      assert.equal(options.method, 'POST');
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.body.get('target'), 'admrul');
      assert.equal(options.body.get('documentId'), '123');
      assert.equal(options.body.get('previewApprovalToken'), 'server-value');
      return new Response('{"chunkVersion":2}');
    };
    for (const invalid of [null, { ...preview, documents: 2 }, { ...preview, target: 'law' }, { ...preview, items: [{ ...preview.items[0], documentId: 124 }] }, { ...preview, items: [{ ...preview.items[0], unexplainedLossSpanCount: 1 }] }, { ...preview, items: [{ ...preview.items[0], approvalToken: '' }] }]) {
      await assert.rejects(api.createDocumentCandidate('admrul', '123', invalid));
    }
    assert.equal(calls, 0);
    assert.deepEqual(await api.createDocumentCandidate('admrul', '123', preview), { chunkVersion: 2 });
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

test('preview sends only one explicit document using the protected parameter contract', async () => {
  assert.equal(typeof api.previewDocumentRebuild, 'function');
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/api/law-data/chunks/rebuild-preview-by-document-ids?target=admrul&documentIds=123');
      assert.equal(options.method, 'POST');
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.body, undefined);
      return new Response(JSON.stringify({ documents: [] }), { headers: { 'Content-Type': 'application/json' } });
    };
    assert.deepEqual(await api.previewDocumentRebuild('admrul', '123'), { documents: [] });
  } finally { globalThis.fetch = original; }
});

test('invalid scope never sends a preview request', async () => {
  assert.equal(typeof api.previewDocumentRebuild, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  try {
    globalThis.fetch = async () => { calls++; throw new Error('Unexpected request'); };
    for (const [target, id] of [['ALL', '123'], ['law', ''], ['law', '1,2'], ['law', '0']]) {
      await assert.rejects(api.previewDocumentRebuild(target, id), /문서/);
    }
    assert.equal(calls, 0);
  } finally { globalThis.fetch = original; }
});

test('unauthenticated preview fails without retrying or changing authentication', async () => {
  assert.equal(typeof api.previewDocumentRebuild, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  try {
    globalThis.fetch = async () => { calls++; return new Response('{}', { status: 401 }); };
    await assert.rejects(api.previewDocumentRebuild('law', '123'), /로그인/);
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});
