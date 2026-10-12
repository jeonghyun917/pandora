import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

test('lexical rebuild uses only the existing authenticated local index endpoint', async () => {
  assert.equal(typeof api.rebuildLexicalIndex, 'function');
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/api/admin/semantic-lexical-index/rebuild');
      assert.equal(options.method, 'POST');
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.body, undefined);
      return new Response('{"activeChunkCount":99}');
    };
    assert.deepEqual(await api.rebuildLexicalIndex(), { activeChunkCount: 99 });
  } finally { globalThis.fetch = original; }
});

test('lexical rebuild never retries authentication or running conflicts', async () => {
  assert.equal(typeof api.rebuildLexicalIndex, 'function');
  const original = globalThis.fetch;
  try {
    for (const status of [401, 409]) {
      let calls = 0;
      globalThis.fetch = async () => { calls++; return new Response('', { status }); };
      await assert.rejects(api.rebuildLexicalIndex());
      assert.equal(calls, 1);
    }
  } finally { globalThis.fetch = original; }
});
