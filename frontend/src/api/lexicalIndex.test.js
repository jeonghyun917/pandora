import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

test('abandoned recovery validates version and sends explicit confirmation once', async () => {
  assert.equal(typeof api.recoverAbandonedLexicalIndex, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  try {
    globalThis.fetch = async (url, options) => {
      calls++;
      assert.match(url, /confirmedAbandoned=true/);
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.method, 'POST');
      return new Response('true');
    };
    await assert.rejects(() => api.recoverAbandonedLexicalIndex('invalid'));
    assert.equal(calls, 0);
    assert.equal(await api.recoverAbandonedLexicalIndex('6dca282f-56c2-477e-b1b1-66d35d5e9d56'), true);
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

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

test('abandoned recovery never retries denied or concurrent requests', async () => {
  const original = globalThis.fetch;
  try {
    for (const status of [401, 409]) {
      let calls = 0;
      globalThis.fetch = async () => { calls++; return new Response('', { status }); };
      await assert.rejects(() => api.recoverAbandonedLexicalIndex('6dca282f-56c2-477e-b1b1-66d35d5e9d56'));
      assert.equal(calls, 1);
    }
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
