import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './lawApi.js';

const eligible = { documentId: 42, status: 'ELIGIBLE', token: 'a'.repeat(64), eligibleChunks: 2,
  storedChunkCount: 3, regeneratedChunkCount: 3, mismatchReason: null };

test('source proof preview requires exactly one explicit document or source hash', async () => {
  assert.equal(typeof api.previewSourceProof, 'function');
  const original = globalThis.fetch;
  const calls = [];
  try {
    globalThis.fetch = async (url, options) => {
      calls.push(url);
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.method, 'GET');
      return new Response(JSON.stringify(eligible));
    };
    for (const [id, hash] of [['', ''], ['42', 'a'.repeat(64)], ['1,2', ''], ['0', ''], ['9007199254740992', ''], ['', 'bad']]) {
      await assert.rejects(api.previewSourceProof(id, hash));
    }
    assert.equal(calls.length, 0);
    await api.previewSourceProof('42', '');
    await api.previewSourceProof('', 'B'.repeat(64));
    assert.deepEqual(calls, ['/api/law-data/rag-source-proof/preview?documentId=42',
      `/api/law-data/rag-source-proof/preview?fileHash=${'b'.repeat(64)}`]);
  } finally { globalThis.fetch = original; }
});

test('apply uses only an eligible server snapshot and sends the JSON metadata-only contract', async () => {
  assert.equal(typeof api.applySourceProof, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  try {
    globalThis.fetch = async (url, options) => {
      calls++;
      assert.equal(url, '/api/law-data/rag-source-proof/apply');
      assert.equal(options.method, 'POST');
      assert.equal(options.credentials, 'same-origin');
      assert.equal(options.headers['Content-Type'], 'application/json');
      assert.deepEqual(JSON.parse(options.body), { documentId: 42, expectedPlanToken: 'a'.repeat(64) });
      return new Response('{"documentId":42,"updatedChunks":2}');
    };
    for (const invalid of [null, { ...eligible, status: 'MISMATCH' }, { ...eligible, token: 'bad' },
      { ...eligible, eligibleChunks: 0 }, { ...eligible, documentId: -1 }]) {
      await assert.rejects(api.applySourceProof(invalid));
    }
    assert.equal(calls, 0);
    assert.deepEqual(await api.applySourceProof(eligible), { documentId: 42, updatedChunks: 2 });
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

test('source proof authentication or stale-plan failure never retries', async () => {
  assert.equal(typeof api.applySourceProof, 'function');
  const original = globalThis.fetch;
  let calls = 0;
  try {
    globalThis.fetch = async () => { calls++; return new Response('{}', { status: 401 }); };
    await assert.rejects(api.previewSourceProof('42', ''), /로그인/);
    await assert.rejects(api.applySourceProof(eligible), /로그인/);
    globalThis.fetch = async () => { calls++; return new Response('{}', { status: 409 }); };
    await assert.rejects(api.applySourceProof(eligible), /미리보기/);
    assert.equal(calls, 3);
  } finally { globalThis.fetch = original; }
});
