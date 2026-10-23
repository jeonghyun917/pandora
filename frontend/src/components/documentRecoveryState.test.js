import test from 'node:test';
import assert from 'node:assert/strict';
import { initialRecoveryState, recoveryReducer as reduce, canRecoveryAction } from './documentRecoveryState.js';

const generation = { id: 'c87a26f6-cc34-4303-bdc2-929657369db3', state: 'INDEX_READY', productSha: 'a'.repeat(40), manifest: 'b'.repeat(64), sourceHash: 'c'.repeat(64), baselineHash: 'd'.repeat(64) };
const ready = { ...initialRecoveryState, documentId: '42', selectedId: generation.id,
  preview: { documentId: 42, productSha: generation.productSha, previewHash: 'e'.repeat(64), candidateCount: 2 },
  status: { mutationsEnabled: true, productSha: generation.productSha, generations: [generation],
    pointer: { documentId: 42, revision: 0, activeGeneration: '00000000-0000-0000-0000-000000000001', sourceHash: generation.sourceHash, baselineHash: generation.baselineHash },
    baseline: { generationId: '00000000-0000-0000-0000-000000000001', chunkCount: 4 } } };

test('pending actions consume stale snapshots and block duplicate submission and scope changes', () => {
  assert.equal(canRecoveryAction(ready, 'promote'), true);
  const pending = reduce(ready, { type: 'start' });
  assert.equal(pending.busy, true);
  assert.equal(pending.status, null);
  assert.equal(pending.preview, null);
  assert.equal(canRecoveryAction(pending, 'promote'), false);
  assert.equal(reduce(pending, { type: 'input', value: '43' }), pending);
  assert.equal(reduce(pending, { type: 'start' }), pending);
});
test('editing document and failed operation require fresh server state', () => {
  const changed = reduce(ready, { type: 'input', value: '43' });
  assert.equal(changed.selectedId, ''); assert.equal(changed.status, null); assert.equal(changed.preview, null);
  const failed = reduce(ready, { type: 'error', message: 'rejected' });
  assert.equal(failed.status, null); assert.equal(canRecoveryAction(failed, 'promote'), false);
});
test('closed server gate, wrong product, unprepared candidate and baseline pointer refuse promotion', () => {
  assert.equal(canRecoveryAction({ ...ready, status: { ...ready.status, mutationsEnabled: false } }, 'promote'), false);
  for (const change of [{ state: 'VALIDATED' }, { productSha: 'f'.repeat(40) }]) {
    assert.equal(canRecoveryAction({ ...ready, status: { ...ready.status, generations: [{ ...generation, ...change }] } }, 'promote'), false);
  }
  assert.equal(canRecoveryAction(ready, 'rollback'), false);
  assert.equal(canRecoveryAction({ ...ready, status: { ...ready.status, pointer: { ...ready.status.pointer, revision: 1, activeGeneration: generation.id } } }, 'rollback'), true);
});
