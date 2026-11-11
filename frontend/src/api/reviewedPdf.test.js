import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

test('reviewed PDF actions never send caller source authority or retry rejected writes', async () => {
  const original = globalThis.fetch; let calls = 0;
  const id = '11111111-1111-1111-1111-111111111111', manifest = 'b'.repeat(64);
  try {
    globalThis.fetch = async (url, options) => {
      calls++; assert.equal(url, '/api/law-data/rag-recovery/reviewed-pdf/create');
      assert.equal(options.credentials, 'same-origin'); assert.equal(options.cache, 'no-store');
      assert.equal(options.headers['X-Pandora-Recovery-CSRF'], 'a'.repeat(64));
      assert.deepEqual(JSON.parse(options.body), { draftId: id, expectedPreparedManifest: manifest });
      return new Response('{}', { status: 409 });
    };
    await assert.rejects(api.runReviewedPdfAction('create', { draftId: id, expectedPreparedManifest: manifest,
      originalPath: 'private', reviewHash: 'forged', draft: {} }, 'a'.repeat(64)));
    assert.equal(calls, 1);
    await assert.rejects(api.runReviewedPdfAction('stage', {}, 'bad'));
    await assert.rejects(api.runReviewedPdfAction('arbitrary', {}, 'a'.repeat(64)));
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});
