import { useReducer, useRef } from 'react';
import { fetchRecoverySession, fetchRecoveryStatus, previewRecovery, runRecoveryAction } from '../api/lawApi';
import { initialRecoveryState, recoveryReducer, canRecoveryAction } from './documentRecoveryState';

const labels = { create: '비활성 후보 생성', validate: '원문 검증', prepare: '격리 자산 준비', promote: '검증된 후보 승격', rollback: '보존 baseline으로 롤백' };
export function DocumentRecoveryPanel() {
  const [state, dispatch] = useReducer(recoveryReducer, initialRecoveryState);
  const inFlight = useRef(false);
  const csrf = useRef('');
  async function load(documentId, selectedId = '', message = '') {
    const [status, session] = await Promise.all([fetchRecoveryStatus(documentId), fetchRecoverySession()]);
    csrf.current = session.csrfToken;
    const preview = status.productSha ? await previewRecovery(documentId) : null;
    dispatch({ type: 'loaded', status, preview, selectedId, message });
  }
  async function refresh(event) {
    event?.preventDefault(); if (inFlight.current) return;
    inFlight.current = true; dispatch({ type: 'start' });
    try { await load(state.documentId, state.selectedId); }
    catch (error) { csrf.current = ''; dispatch({ type: 'error', message: error.message }); }
    finally { inFlight.current = false; }
  }
  async function run(action) {
    if (inFlight.current || !canRecoveryAction(state, action)) return;
    const snapshot = state; const selected = snapshot.status.generations.find((item) => item.id === snapshot.selectedId);
    inFlight.current = true; dispatch({ type: 'start' });
    try {
      const fresh = await fetchRecoveryStatus(snapshot.documentId);
      if (JSON.stringify(fresh.pointer) !== JSON.stringify(snapshot.status.pointer) || fresh.productSha !== snapshot.status.productSha || !fresh.mutationsEnabled)
        throw new Error('서버 세대가 바뀌었습니다. 상태를 새로 확인한 뒤 작업하세요.');
      let body;
      if (action === 'create') body = { documentId: Number(snapshot.documentId), expectedPreviewHash: snapshot.preview.previewHash };
      else if (action === 'promote') body = { generationId: selected.id, expectedRevision: fresh.pointer.revision, expectedManifest: selected.manifest };
      else if (action === 'rollback') body = { documentId: Number(snapshot.documentId), expectedActiveGeneration: fresh.pointer.activeGeneration,
        expectedRevision: fresh.pointer.revision, restoreGeneration: snapshot.status.baseline.generationId };
      else body = { generationId: selected.id };
      const result = await runRecoveryAction(action, body, csrf.current);
      const message = action === 'validate' && !result.passed ? `원문 검증 실패: ${(result.failures ?? []).join(', ')}` : `${labels[action]} 완료. 다음 단계는 자동 실행하지 않습니다.`;
      await load(snapshot.documentId, result.id ?? snapshot.selectedId, message);
    } catch (error) { dispatch({ type: 'error', message: error.message }); }
    finally { inFlight.current = false; }
  }
  const selected = state.status?.generations.find((item) => item.id === state.selectedId);
  return <section className="admin-panel admin-panel-wide document-recovery-panel" aria-labelledby="document-recovery-title" aria-busy={state.busy}>
    <h2 id="document-recovery-title">문서 세대 복구</h2>
    <p>기존 청크·벡터·키워드를 보존합니다. 후보 생성 → 원문 검증 → 격리 자산 준비 → 승격을 개별 실행하며, 실패 시 자동 재시도하지 않습니다.</p>
    <form className="rebuild-preview-form" onSubmit={refresh}>
      <label>문서 ID<input value={state.documentId} inputMode="numeric" pattern="[1-9][0-9]*" required disabled={state.busy}
        onChange={(event) => dispatch({ type: 'input', value: event.target.value })} /></label>
      <button type="submit" disabled={state.busy}>{state.busy ? '검증 처리 중…' : '현재 상태·미리보기 확인'}</button>
    </form>
    {state.busy && <p role="status">작업 중입니다. 중복 실행은 차단됩니다.</p>}
    {state.error && <p role="alert">{state.error}</p>}
    {state.message && <p role="status">{state.message}</p>}
    {state.status && <div>
      <p role="status">운영 변경 게이트: {state.status.mutationsEnabled ? '열림 — 개별 안전 검증은 계속 적용' : '닫힘 — 실 Qdrant·호환 writer 및 릴리스 검증 전 변경 불가'}</p>
      <p>현재 revision: {state.status.pointer?.revision ?? '미등록'} · baseline 보존 영수증: {state.status.baseline ? '확인됨' : '아직 없음'}</p>
      {state.preview && <p>기존 청크 {state.preview.baselineCount}개 · 재생성 후보 {state.preview.candidateCount}개 · 원본 SHA {state.preview.sourceHash}</p>}
      {!state.status.productSha && <p>검증된 제품 SHA가 설정되지 않아 미리보기·변경을 실행할 수 없습니다.</p>}
      <div className="rebuild-preview-form">
        <label>작업 대상 세대<select value={state.selectedId} disabled={state.busy} onChange={(event) => dispatch({ type: 'select', value: event.target.value })}>
          <option value="">세대 선택</option>{state.status.generations.map((item) => <option key={item.id} value={item.id}>{item.state} · {item.id}</option>)}
        </select></label>
        {Object.entries(labels).map(([action, label]) => <button key={action} type="button" disabled={!canRecoveryAction(state, action)} onClick={() => run(action)}>{label}</button>)}
      </div>
      <p>선택 세대 상태: {selected?.state ?? '없음'}. 원문 검증 통과는 답변 품질 평가 PASS를 뜻하지 않습니다.</p>
    </div>}
  </section>;
}
