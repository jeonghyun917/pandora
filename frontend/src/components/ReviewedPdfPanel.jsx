import { useRef, useState } from 'react';
import { fetchRecoverySession, fetchRecoveryStatus, fetchReviewedPdfReview, inspectReviewedPdf, runReviewedPdfAction } from '../api/lawApi';
import { canReviewedPdfAction, reviewedPdfGenerations, reviewedPdfSnapshotKey } from './reviewedPdfState';

const labels = { register: '보존 기준본 등록', stage: '승인 원문 보관·준비 확인', create: '비활성 PDF 후보 생성',
  validate: 'PDF 원문·멤버 검증', prepare: 'PDF 격리 자산 준비', promote: '검증된 PDF 승격', rollback: '보존 원본으로 복귀' };
const initial = { review: null, status: null, selectedId: '', staged: null, preparation: null, busy: false, error: '', message: '' };
export function ReviewedPdfPanel() {
  const [state, setState] = useState(initial);
  const inFlight = useRef(false), csrf = useRef('');
  async function readCurrent() {
    const review = await fetchReviewedPdfReview();
    const [status, session] = await Promise.all([fetchRecoveryStatus(review.documentId), fetchRecoverySession()]);
    csrf.current = session.csrfToken;
    return { review, status };
  }
  function loaded(current, previous, overrides = {}) {
    const same = current.review.reviewHash === previous.review?.reviewHash;
    const next = { ...initial, ...current, ...(same ? { staged: previous.staged, preparation: previous.preparation } : {}), ...overrides };
    const choices = reviewedPdfGenerations(next);
    next.selectedId = choices.find((g) => g.id === (overrides.selectedId ?? previous.selectedId))?.id
      ?? choices.find((g) => g.id === current.status.pointer?.activeGeneration)?.id ?? choices[0]?.id ?? '';
    return next;
  }
  async function refresh() {
    if (inFlight.current) return;
    inFlight.current = true; setState((s) => ({ ...s, busy: true, error: '', message: '' }));
    try { setState(loaded(await readCurrent(), state)); }
    catch (error) { csrf.current = ''; setState({ ...initial, error: error.message }); }
    finally { inFlight.current = false; }
  }
  async function run(action) {
    if (inFlight.current || !canReviewedPdfAction(state, action)) return;
    const snapshot = state;
    inFlight.current = true; setState((s) => ({ ...s, busy: true, error: '', message: '' }));
    try {
      const current = await readCurrent();
      if (reviewedPdfSnapshotKey(current) !== reviewedPdfSnapshotKey(snapshot)) {
        setState(loaded(current, snapshot, { error: '서버 검토본 또는 세대가 바뀌었습니다. 새 상태를 확인한 뒤 실행하세요.' }));
        return;
      }
      const selected = reviewedPdfGenerations(snapshot).find((g) => g.id === snapshot.selectedId);
      let body = {};
      if (action === 'create') body = { draftId: snapshot.staged.id, expectedPreparedManifest: snapshot.preparation.manifest };
      else if (action === 'validate') body = { generationId: selected.id, expectedPreparedManifest: snapshot.preparation.manifest };
      else if (action === 'prepare') body = { generationId: selected.id };
      else if (action === 'promote') body = { generationId: selected.id, expectedRevision: current.status.pointer.revision, expectedManifest: selected.manifest };
      else if (action === 'rollback') body = { documentId: current.review.documentId, expectedActiveGeneration: current.status.pointer.activeGeneration,
        expectedRevision: current.status.pointer.revision, restoreGeneration: current.status.baseline.generationId };
      const result = await runReviewedPdfAction(action, body, csrf.current);
      const extra = action === 'stage' ? { staged: result, preparation: await inspectReviewedPdf(result.id) }
        : action === 'create' ? { selectedId: result.id } : {};
      setState(loaded(await readCurrent(), snapshot, { ...extra, message: `${labels[action]} 완료. 다음 변경은 자동 실행하지 않습니다.` }));
    } catch (error) {
      csrf.current = ''; setState({ ...initial, error: `${error.message} 서버 상태를 다시 읽기 전에는 변경을 재실행하지 마세요.` });
    } finally { inFlight.current = false; }
  }
  const choices = reviewedPdfGenerations(state), selected = choices.find((g) => g.id === state.selectedId);
  return <section className="admin-panel admin-panel-wide document-recovery-panel" aria-labelledby="reviewed-pdf-title" aria-busy={state.busy}>
    <h2 id="reviewed-pdf-title">검토된 PDF 원문 갱신</h2>
    <p>서버가 승인한 원문·검토 기록만 사용합니다. 기존 원본은 보존하며, 승격 전까지 검색 내용은 바뀌지 않습니다. 원문·자산 검증은 답변 품질 PASS가 아닙니다.</p>
    <button type="button" disabled={state.busy} onClick={refresh}>{state.busy ? '검증 처리 중…' : '서버 검토본·현재 상태 확인'}</button>
    {state.busy && <p role="status">처리 중입니다. 중복 실행과 자동 재시도는 하지 않습니다.</p>}
    {state.error && <p role="alert">{state.error}</p>}
    {state.message && <p role="status">{state.message}</p>}
    {state.review && <div>
      <p>대상 문서 {state.review.documentId} · {state.review.title} · 검토 단위 {state.review.units}개</p>
      <p>제품 SHA: {state.review.productSha}<br />원문 SHA: {state.review.sourceHash}<br />검토 SHA: {state.review.reviewHash}</p>
      <p role="status">PDF 전용 변경 게이트: {state.review.mutationsEnabled ? '열림 — 모든 개별 검증은 유지' : '닫힘 — 서버 승인 조건 충족 전 변경 불가'}
        {' · '}현재 revision: {state.status?.pointer?.revision ?? '미등록'} · 보존 영수증: {state.status?.baseline ? '확인됨' : '없음'}</p>
      {state.preparation && <p>보관본 재생성 결과: {state.preparation.chunks}개 청크 · {state.preparation.units}개 검토 단위<br />준비 manifest: {state.preparation.manifest}</p>}
      <div className="rebuild-preview-form">
        <label>검토본 세대<select value={state.selectedId} disabled={state.busy} onChange={(e) => setState((s) => ({ ...s, selectedId: e.target.value }))}>
          <option value="">선택된 세대 없음</option>{choices.map((g) => <option key={g.id} value={g.id}>{g.state} · {g.id}</option>)}
        </select></label>
        {Object.entries(labels).map(([action, label]) => <button key={action} type="button" disabled={!canReviewedPdfAction(state, action)} onClick={() => run(action)}>{label}</button>)}
      </div>
      <p>선택 상태: {selected?.state ?? '없음'}. 실패·폐기된 후보는 자동으로 다시 만들지 않습니다. 결과가 중단되면 현재 상태부터 확인하세요.</p>
    </div>}
  </section>;
}
