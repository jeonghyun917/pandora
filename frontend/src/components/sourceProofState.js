import { canApplySourceProof } from '../api/lawApi.js';

export const initialSourceProofState = {
  documentId: '', fileHash: '', preview: null, busy: false, error: '', result: null,
};

export function sourceProofReducer(state, action) {
  switch (action.type) {
    case 'input':
      if (state.busy || !['documentId', 'fileHash'].includes(action.field)) return state;
      return { ...state, [action.field]: action.value, preview: null, result: null, error: '' };
    case 'start':
      if (state.busy) return state;
      return { ...state, busy: true, preview: null, result: null, error: '' };
    case 'apply':
      if (state.busy || !canApplySourceProof(state.preview)) return state;
      return { ...state, busy: true, preview: null, result: null, error: '' };
    case 'preview':
      return { ...state, busy: false, preview: action.value };
    case 'result':
      return { ...state, busy: false, preview: null, result: action.value };
    case 'error':
      return { ...state, busy: false, preview: null, error: action.message };
    default:
      return state;
  }
}

