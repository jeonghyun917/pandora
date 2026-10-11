import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

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
