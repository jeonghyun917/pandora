export const initialRecoveryState = { documentId: '', selectedId: '', preview: null, status: null, busy: false, error: '', message: '' };
export function recoveryReducer(state, action) {
  switch (action.type) {
    case 'input': return state.busy ? state : { ...initialRecoveryState, documentId: action.value };
    case 'select': return state.busy ? state : { ...state, selectedId: action.value };
    case 'start': return state.busy ? state : { ...state, busy: true, preview: null, status: null, error: '', message: '' };
    case 'loaded': {
      const generations = action.status?.generations ?? [];
      const selected = generations.find((item) => item.id === action.selectedId)
        ?? generations.find((item) => item.productSha === action.status?.productSha && ['INDEX_READY', 'VALIDATED', 'PENDING'].includes(item.state));
      return { ...state, busy: false, status: action.status, preview: action.preview ?? null,
        selectedId: selected?.id ?? '', error: '', message: action.message ?? '' };
    }
    case 'error': return { ...state, busy: false, preview: null, status: null, error: action.message, message: '' };
    default: return state;
  }
}
export function canRecoveryAction(state, action) {
  const status = state.status;
  if (state.busy || !status?.mutationsEnabled || !/^[0-9a-f]{40}$/.test(status.productSha ?? '')) return false;
  const pointer = status.pointer;
  const selected = status.generations?.find((item) => item.id === state.selectedId);
  if (action === 'create') return state.preview?.documentId === Number(state.documentId)
    && state.preview?.productSha === status.productSha && /^[0-9a-f]{64}$/.test(state.preview?.previewHash ?? '') && state.preview?.candidateCount > 0;
  if (action === 'rollback') return Boolean(pointer && status.baseline?.chunkCount > 0 && pointer.revision > 0
    && pointer.activeGeneration !== status.baseline.generationId);
  if (!selected || selected.productSha !== status.productSha) return false;
  if (action === 'validate') return ['PENDING', 'VALIDATED'].includes(selected.state);
  if (action === 'prepare') return ['VALIDATED', 'INDEX_READY'].includes(selected.state) && Boolean(status.baseline);
  if (action === 'promote') return selected.state === 'INDEX_READY' && Boolean(pointer && status.baseline)
    && selected.id !== pointer.activeGeneration && selected.sourceHash === pointer.sourceHash && selected.baselineHash === pointer.baselineHash;
  return false;
}
