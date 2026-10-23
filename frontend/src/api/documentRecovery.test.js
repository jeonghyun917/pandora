import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

test('recovery writes use only explicit contracts with session-bound CSRF and no retries', async () => {
  assert.equal(typeof api.runRecoveryAction, 'function');
  const original = globalThis.fetch; let calls = 0;
  try {
    globalThis.fetch = async (url, options) => {
      calls++; assert.equal(url, '/api/law-data/rag-recovery/create');
      assert.equal(options.credentials, 'same-origin'); assert.equal(options.cache, 'no-store');
      assert.equal(options.headers['X-Pandora-Recovery-CSRF'], 'a'.repeat(64));
      assert.deepEqual(JSON.parse(options.body), { documentId: 42, expectedPreviewHash: 'b'.repeat(64) });
      return new Response('{}', { status: 409 });
    };
    await assert.rejects(api.runRecoveryAction('create', { documentId: 42, expectedPreviewHash: 'b'.repeat(64), state: 'READY' }, 'a'.repeat(64)));
    assert.equal(calls, 1);
    await assert.rejects(api.runRecoveryAction('create', { documentId: 42 }, 'bad'));
    await assert.rejects(api.runRecoveryAction('arbitrary', {}, 'a'.repeat(64)));
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});
