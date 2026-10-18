import test from 'node:test';
import assert from 'node:assert/strict';
import { initialSourceProofState, sourceProofReducer as reduce } from './sourceProofState.js';

const preview = { documentId: 42, status: 'ELIGIBLE', token: 'a'.repeat(64), eligibleChunks: 2 };

test('editing either scope invalidates the prior preview and result', () => {
  const ready = { ...initialSourceProofState, documentId: '42', preview, result: { updatedChunks: 2 } };
  for (const field of ['documentId', 'fileHash']) {
    const changed = reduce(ready, { type: 'input', field, value: '43' });
    assert.equal(changed[field], '43');
    assert.equal(changed.preview, null);
    assert.equal(changed.result, null);
  }
});

test('apply consumes the preview before completion and never permits repeated apply', () => {
  const ready = { ...initialSourceProofState, documentId: '42', preview };
  const applying = reduce(ready, { type: 'apply' });
  assert.equal(applying.preview, null);
  assert.equal(applying.busy, true);
  assert.equal(reduce(applying, { type: 'input', field: 'documentId', value: '99' }), applying);
  const complete = reduce(applying, { type: 'result', value: { documentId: 42, updatedChunks: 2 } });
  assert.equal(complete.busy, false);
  assert.deepEqual(complete.result, { documentId: 42, updatedChunks: 2 });
  assert.equal(reduce(complete, { type: 'apply' }), complete);
});

test('failed apply leaves no reusable preview and a fresh preview can be requested', () => {
  const applying = reduce({ ...initialSourceProofState, preview }, { type: 'apply' });
  const failed = reduce(applying, { type: 'error', message: 'stale' });
  assert.equal(failed.preview, null);
  assert.equal(failed.error, 'stale');
  assert.equal(failed.busy, false);
  const loading = reduce(failed, { type: 'start' });
  assert.equal(loading.error, '');
  assert.equal(loading.busy, true);
  const ready = reduce(loading, { type: 'preview', value: preview });
  assert.equal(ready.preview, preview);
  assert.equal(ready.busy, false);
});

test('ineligible or malformed snapshots cannot enter apply state', () => {
  for (const invalid of [null, { ...preview, eligibleChunks: 0 }, { ...preview, status: 'MISMATCH' }, { ...preview, token: '' }]) {
    const state = { ...initialSourceProofState, preview: invalid };
    assert.equal(reduce(state, { type: 'apply' }), state);
  }
});
