const hash = /^[0-9a-f]{64}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export function reviewedPdfGenerations(state) {
  const r = state.review;
  return (state.status?.generations ?? []).filter((g) => r?.generationIds?.includes(g.id)
    && g.id !== state.status.baseline?.generationId && g.documentId === r.documentId
    && g.productSha === r.productSha && g.sourceHash === r.sourceHash && g.baselineHash === r.baselineHash);
}
export function reviewedPdfSnapshotKey(state) { return JSON.stringify([state.review, state.status]); }
export function canReviewedPdfAction(state, action) {
  const r = state.review, s = state.status;
  if (state.busy || !r?.mutationsEnabled || !s || !/^[0-9a-f]{40}$/.test(r.productSha ?? '')
      || r.productSha !== s.productSha || !hash.test(r.sourceHash ?? '') || !hash.test(r.baselineHash ?? '') || !hash.test(r.reviewHash ?? '')) return false;
  const pointer = s.pointer, baseline = s.baseline, candidates = reviewedPdfGenerations(state);
  if (pointer && (pointer.documentId !== r.documentId || pointer.baselineHash !== r.baselineHash)) return false;
  if (action === 'register') return !pointer && !baseline && !(s.generations?.length);
  if (!pointer || !baseline || baseline.baselineHash !== r.baselineHash || !(baseline.chunkCount > 0)) return false;
  if (action === 'stage') return pointer.activeGeneration !== state.selectedId;
  const p = state.preparation;
  const prepared = uuid.test(state.staged?.id ?? '') && state.staged.reviewHash === r.reviewHash
    && p?.draftId === state.staged.id && p.reviewHash === r.reviewHash && hash.test(p.manifest ?? '') && p.chunks > 0 && p.units === r.units;
  if (action === 'create') return prepared && candidates.length === 0;
  const selected = candidates.find((g) => g.id === state.selectedId);
  if (!selected) return false;
  if (action === 'validate') return prepared && selected.state === 'PENDING';
  if (action === 'prepare') return selected.state === 'VALIDATED';
  if (action === 'promote') return selected.state === 'INDEX_READY' && selected.id !== pointer.activeGeneration;
  if (action === 'rollback') return selected.state === 'ACTIVE' && pointer.activeGeneration === selected.id
    && pointer.revision > 0 && pointer.activeGeneration !== baseline.generationId;
  return false;
}
