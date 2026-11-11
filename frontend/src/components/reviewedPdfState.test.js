import test from 'node:test';
import assert from 'node:assert/strict';
import { canReviewedPdfAction, reviewedPdfSnapshotKey } from './reviewedPdfState.js';
const id = '11111111-1111-1111-1111-111111111111', draft = '22222222-2222-2222-2222-222222222222';
function fixture() {
  const review = { documentId: 42, productSha: 'a'.repeat(40), sourceHash: 'b'.repeat(64), baselineHash: 'c'.repeat(64),
    reviewHash: 'd'.repeat(64), units: 7, mutationsEnabled: true, generationIds: [id] };
  const selected = { id, documentId: 42, productSha: review.productSha, sourceHash: review.sourceHash,
    baselineHash: review.baselineHash, state: 'PENDING', manifest: 'e'.repeat(64) };
  return { review, status: { productSha: review.productSha, mutationsEnabled: true,
    pointer: { documentId: 42, revision: 0, activeGeneration: draft, sourceHash: 'f'.repeat(64), baselineHash: review.baselineHash },
    baseline: { generationId: draft, baselineHash: review.baselineHash, chunkCount: 64 }, generations: [selected] },
    selectedId: id, staged: { id: draft, reviewHash: review.reviewHash },
    preparation: { draftId: draft, reviewHash: review.reviewHash, manifest: 'e'.repeat(64), chunks: 176, units: 7 }, busy: false };
}
test('only the next owned PDF state can mutate; failures cannot create another candidate', () => {
  const state = fixture();
  assert.equal(canReviewedPdfAction(state, 'validate'), true);
  assert.equal(canReviewedPdfAction(state, 'prepare'), false);
  assert.equal(canReviewedPdfAction(state, 'create'), false);
  state.status.generations[0].state = 'FAILED';
  assert.equal(canReviewedPdfAction(state, 'validate'), false);
  assert.equal(canReviewedPdfAction(state, 'create'), false);
  state.status.generations[0].state = 'INDEX_READY';
  assert.equal(canReviewedPdfAction(state, 'promote'), true);
  assert.notEqual(state.status.pointer.sourceHash, state.review.sourceHash);
  state.status.generations[0].sourceHash = '0'.repeat(64);
  assert.equal(canReviewedPdfAction(state, 'promote'), false);
});
test('the combined reviewed gate, exact product, current manifest and stable snapshot are required', () => {
  const state = fixture(), key = reviewedPdfSnapshotKey(state);
  state.review.mutationsEnabled = false;
  assert.equal(canReviewedPdfAction(state, 'validate'), false);
  assert.notEqual(reviewedPdfSnapshotKey(state), key);
  state.review.mutationsEnabled = true; state.status.mutationsEnabled = false;
  assert.equal(canReviewedPdfAction(state, 'stage'), true);
  state.status.productSha = '0'.repeat(40);
  assert.equal(canReviewedPdfAction(state, 'stage'), false);
  state.status.productSha = state.review.productSha; state.preparation.reviewHash = '0'.repeat(64);
  assert.equal(canReviewedPdfAction(state, 'validate'), false);
  state.preparation.reviewHash = state.review.reviewHash; state.busy = true;
  assert.equal(canReviewedPdfAction(state, 'validate'), false);
});
test('same-byte preserved baseline is not a reviewed candidate; only exact server-owned IDs qualify', () => {
  const state = fixture();
  state.status.pointer.sourceHash = state.review.sourceHash;
  state.status.generations = [{ ...state.status.generations[0], id: draft, state: 'ACTIVE' }];
  state.review.generationIds = [];
  state.selectedId = '';
  assert.equal(canReviewedPdfAction(state, 'stage'), true);
  assert.equal(canReviewedPdfAction(state, 'create'), true);
  state.status.generations.push({ ...fixture().status.generations[0], state: 'FAILED' });
  assert.equal(canReviewedPdfAction(state, 'create'), true);
  state.review.generationIds = [id];
  assert.equal(canReviewedPdfAction(state, 'create'), false);
});
