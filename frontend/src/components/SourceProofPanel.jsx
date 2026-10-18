import { useReducer, useRef } from 'react';
import { applySourceProof, canApplySourceProof, previewSourceProof } from '../api/lawApi';
import { initialSourceProofState, sourceProofReducer } from './sourceProofState';

export function SourceProofPanel() {
  const [state, dispatch] = useReducer(sourceProofReducer, initialSourceProofState);
  const inFlight = useRef(false);
  const consumedPreview = useRef(null);

  async function preview(event) {
    event.preventDefault();
    if (inFlight.current) return;
    inFlight.current = true;
    dispatch({ type: 'start' });
    try {
      const value = await previewSourceProof(state.documentId, state.fileHash);
      consumedPreview.current = null;
      dispatch({ type: 'preview', value });
    } catch (error) {
      dispatch({ type: 'error', message: error.message });
    } finally { inFlight.current = false; }
  }

  async function apply() {
    const snapshot = state.preview;
    if (inFlight.current || !canApplySourceProof(snapshot) || consumedPreview.current === snapshot) return;
    inFlight.current = true;
    consumedPreview.current = snapshot;
    dispatch({ type: 'apply' });
    try {
      dispatch({ type: 'result', value: await applySourceProof(snapshot) });
    } catch (error) {
      dispatch({ type: 'error', message: error.message });
    } finally { inFlight.current = false; }
  }

  return (
    <section className="admin-panel admin-panel-wide" aria-label="원문 표 메타데이터 보완">
      <h2>원문 표 메타데이터 보완</h2>
      <p>문서 ID 또는 원본 파일 SHA-256 중 하나로 계획을 확인합니다. 기존 청크의 원문 표 증명 메타데이터만 보완하며 재수집·재청킹·활성화·색인·임베딩은 실행하지 않습니다.</p>
      <form className="rebuild-preview-form" onSubmit={preview}>
        <label>문서 ID
          <input value={state.documentId} inputMode="numeric" pattern="[1-9][0-9]*" disabled={state.busy}
            onChange={(event) => dispatch({ type: 'input', field: 'documentId', value: event.target.value })} />
        </label>
        <label>원본 파일 SHA-256 (문서 ID 미입력 시)
          <input value={state.fileHash} pattern="[0-9a-fA-F]{64}" maxLength={64} spellCheck={false} disabled={state.busy}
            onChange={(event) => dispatch({ type: 'input', field: 'fileHash', value: event.target.value })} />
        </label>
        <button type="submit" disabled={state.busy}>{state.busy ? '처리 중…' : '표 메타데이터 미리보기'}</button>
      </form>
      {state.busy && <p role="status">처리 중입니다. 중복 실행하지 마세요.</p>}
      {state.error && <p role="alert">{state.error}</p>}
      {state.preview && (
        <div role="status" aria-label="표 메타데이터 미리보기 결과">
          <p>문서 ID: {state.preview.documentId} · 상태: {state.preview.status}</p>
          <p>저장 청크: {state.preview.storedChunkCount} · 원문 재생성 청크: {state.preview.regeneratedChunkCount} · 보완 가능 청크: {state.preview.eligibleChunks}</p>
          {state.preview.mismatchReason && <p>불일치 사유: {state.preview.mismatchReason}</p>}
        </div>
      )}
      <button type="button" disabled={state.busy || !canApplySourceProof(state.preview)} onClick={apply}>검증된 표 메타데이터만 적용</button>
      {state.result && <p role="status">문서 {state.result.documentId}: 메타데이터 {state.result.updatedChunks}개 청크 보완 완료. 다시 적용하려면 새 미리보기가 필요합니다.</p>}
    </section>
  );
}
