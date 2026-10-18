import { useEffect, useMemo, useState } from 'react';
import {
  Activity,
  ArrowLeft,
  BarChart3,
  DatabaseZap,
  FileText,
  Layers3,
  RefreshCw,
  Send,
} from 'lucide-react';
import { fetchAdminOperations, fetchAdminPipelines, runMinistryCollection, previewDocumentRebuild, createDocumentCandidate, indexDocumentCandidate, resumeCandidateIndex, activateDocumentCandidate, rebuildLexicalIndex, recoverAbandonedLexicalIndex } from '../api/lawApi';
import { LandingConstellation } from './LandingPage';
import { SourceProofPanel } from './SourceProofPanel';

const agencyOptions = [
  { value: 'ALL', label: 'All sources' },
  { value: 'GOV', label: 'Policy briefing' },
  { value: 'MOIS', label: 'MOIS' },
  { value: 'MCST', label: 'MCST' },
  { value: 'MSIT', label: 'MSIT' },
  { value: 'PIPC', label: 'PIPC' },
];

function formatNumber(value) {
  return Number(value ?? 0).toLocaleString('ko-KR');
}

function formatMetricValue(metric) {
  const value = Number(metric?.value ?? 0);
  if (value < 0) {
    return '확인 필요';
  }
  if (metric?.key === 'qualityEvalGate') {
    return `${formatNumber(value)}%`;
  }
  return formatNumber(value);
}

function progressPercent(done, total) {
  if (!total) {
    return 0;
  }
  return Math.min(100, Math.round((Number(done ?? 0) / Number(total)) * 100));
}

function statusTone(status) {
  if (['완료', '정상', '대기 없음', 'SUCCESS', 'INGESTED', 'INDEXED'].includes(status)) {
    return 'good';
  }
  if (['오류 확인', '오류', 'FAILED'].includes(status)) {
    return 'bad';
  }
  if (['Batch 진행', 'RUNNING', 'PARTIAL_SUCCESS', '미확인'].includes(status)) {
    return 'warn';
  }
  return 'normal';
}

function pipelineStatus(documents, chunks, indexedChunks, failedEmbeddings = 0, activeBatches = 0) {
  if (failedEmbeddings > 0) {
    return '오류 확인';
  }
  if (activeBatches > 0) {
    return 'Batch 진행';
  }
  if (chunks === 0 || documents === 0) {
    return '대기 없음';
  }
  if (indexedChunks >= chunks) {
    return '완료';
  }
  return '임베딩 대기';
}

function expandPipelineCards(pipelines) {
  return (pipelines ?? []).flatMap((pipeline) => {
    const breakdowns = pipeline.breakdowns ?? [];
    if (pipeline.key !== 'official_doc' || breakdowns.length === 0) {
      return [pipeline];
    }

    return breakdowns.map((item, index) => {
      const chunks = Number(item.chunks ?? 0);
      const indexedChunks = Number(item.indexedChunks ?? 0);
      const documents = Number(item.documents ?? 0);
      return {
        ...pipeline,
        key: `${pipeline.key}-${index}-${item.label}`,
        pageName: item.label,
        sourceType: '공식 가이드 문서',
        documents,
        chunkedDocuments: chunks > 0 ? documents : 0,
        chunks,
        indexedChunks,
        pendingChunks: Math.max(0, chunks - indexedChunks),
        failedEmbeddings: 0,
        activeBatches: 0,
        submittedBatches: 0,
        ingestedBatches: 0,
        status: pipelineStatus(documents, chunks, indexedChunks),
        breakdowns: [],
      };
    });
  });
}

export function AdminPage({ onBack }) {
  const [pipelineOverview, setPipelineOverview] = useState(null);
  const [operationsOverview, setOperationsOverview] = useState(null);
  const [agency, setAgency] = useState('ALL');
  const [fillQueue, setFillQueue] = useState(true);
  const [maxArticles, setMaxArticles] = useState(20);
  const [maxAttachments, setMaxAttachments] = useState(3);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [adminSection, setAdminSection] = useState('pipelines');
  const [previewTarget, setPreviewTarget] = useState('admrul');
  const [previewDocumentId, setPreviewDocumentId] = useState('');
  const [previewResult, setPreviewResult] = useState(null);
  const [previewError, setPreviewError] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [candidateLoading, setCandidateLoading] = useState(false);
  const [candidateResult, setCandidateResult] = useState(null);
  const [candidateIndexStarted, setCandidateIndexStarted] = useState(false);
  const [candidateIndexResult, setCandidateIndexResult] = useState(null);
  const [resumeVersion, setResumeVersion] = useState('');
  const [resumeLimit, setResumeLimit] = useState('');
  const [activationStarted, setActivationStarted] = useState(false);
  const [activationResult, setActivationResult] = useState(null);
  const [lexicalStarted, setLexicalStarted] = useState(false);
  const [lexicalLoading, setLexicalLoading] = useState(false);
  const [lexicalResult, setLexicalResult] = useState(null);
  const [lexicalError, setLexicalError] = useState('');
  const [recoveryVersion, setRecoveryVersion] = useState('');
  const [recoveryConfirmed, setRecoveryConfirmed] = useState(false);
  const [recoveryStarted, setRecoveryStarted] = useState(false);
  const [recoveryResult, setRecoveryResult] = useState('');

  async function recoverLexical(event) {
    event.preventDefault();
    if (!recoveryConfirmed || recoveryStarted || lexicalLoading || candidateLoading || previewLoading) return;
    setRecoveryStarted(true);
    try {
      const recovered = await recoverAbandonedLexicalIndex(recoveryVersion);
      setRecoveryResult(recovered ? '중단 버전을 FAILED로 처리했습니다. 기존 READY는 유지됩니다.' : '변경되지 않았습니다. 해당 버전은 BUILDING이 아닙니다.');
    } catch (error) {
      setLexicalError(error.message);
    }
  }

  async function refreshLexicalIndex() {
    if (lexicalStarted || candidateLoading || previewLoading) return;
    setLexicalStarted(true);
    setLexicalLoading(true);
    try {
      setLexicalResult(await rebuildLexicalIndex());
    } catch (error) {
      setLexicalError(error.message);
    } finally {
      setLexicalLoading(false);
    }
  }

  async function previewRebuild(event) {
    event.preventDefault();
    if (previewLoading || candidateLoading) return;
    setCandidateResult(null);
    setPreviewLoading(true);
    setPreviewResult(null);
    setPreviewError('');
    try {
      setPreviewResult(await previewDocumentRebuild(previewTarget, previewDocumentId));
    } catch (error) {
      setPreviewError(error.message);
    } finally {
      setPreviewLoading(false);
    }
  }

  async function createCandidate() {
    if (previewLoading || candidateLoading || candidateResult || !previewResult) return;
    setCandidateLoading(true);
    setPreviewError('');
    const approvedPreview = previewResult;
    setPreviewResult(null);
    try {
      setCandidateResult(await createDocumentCandidate(previewTarget, previewDocumentId, approvedPreview));
    } catch (error) {
      setPreviewError(error.message);
    } finally {
      setCandidateLoading(false);
    }
  }

  async function indexCandidate() {
    if (candidateLoading || candidateIndexStarted || !candidateResult) return;
    setCandidateIndexStarted(true);
    setCandidateLoading(true);
    setPreviewError('');
    try {
      setCandidateIndexResult(await indexDocumentCandidate(previewTarget, candidateResult));
    } catch (error) {
      setPreviewError(error.message);
    } finally {
      setCandidateLoading(false);
    }
  }

  async function resumeIndex(event) {
    event.preventDefault();
    if (candidateLoading || previewLoading || candidateIndexStarted) return;
    setCandidateIndexStarted(true);
    setCandidateLoading(true);
    setPreviewError('');
    try {
      setCandidateIndexResult(await resumeCandidateIndex(previewTarget, previewDocumentId, resumeVersion, resumeLimit));
    } catch (error) {
      setPreviewError(error.message);
    } finally {
      setCandidateLoading(false);
    }
  }

  async function activateCandidate() {
    if (candidateLoading || activationStarted) return;
    setActivationStarted(true);
    setCandidateLoading(true);
    setPreviewError('');
    try {
      setActivationResult(await activateDocumentCandidate(previewDocumentId, resumeVersion));
    } catch (error) {
      setPreviewError(error.message);
    } finally {
      setCandidateLoading(false);
    }
  }

  const generatedAt = adminSection === 'operations'
    ? operationsOverview?.generatedAt ?? pipelineOverview?.generatedAt
    : pipelineOverview?.generatedAt;
  const metrics = useMemo(
    () => (adminSection === 'operations' ? operationsOverview?.metrics : pipelineOverview?.metrics) ?? [],
    [adminSection, operationsOverview, pipelineOverview]
  );
  const pipelines = useMemo(() => pipelineOverview?.pipelines ?? [], [pipelineOverview]);
  const displayPipelines = useMemo(() => expandPipelineCards(pipelines), [pipelines]);
  const pipelineSummary = useMemo(() => displayPipelines.reduce((summary, pipeline) => ({
    documents: summary.documents + Number(pipeline.documents ?? 0),
    chunkedDocuments: summary.chunkedDocuments + Number(pipeline.chunkedDocuments ?? 0),
    chunks: summary.chunks + Number(pipeline.chunks ?? 0),
    indexedChunks: summary.indexedChunks + Number(pipeline.indexedChunks ?? 0),
    pendingChunks: summary.pendingChunks + Number(pipeline.pendingChunks ?? 0),
    activeBatches: summary.activeBatches + Number(pipeline.activeBatches ?? 0),
    failedEmbeddings: summary.failedEmbeddings + Number(pipeline.failedEmbeddings ?? 0),
    pipelineCount: summary.pipelineCount + 1,
  }), {
    documents: 0,
    chunkedDocuments: 0,
    chunks: 0,
    indexedChunks: 0,
    pendingChunks: 0,
    activeBatches: 0,
    failedEmbeddings: 0,
    pipelineCount: 0,
  }), [displayPipelines]);
  const sources = useMemo(() => operationsOverview?.sources ?? [], [operationsOverview]);
  const batches = useMemo(() => operationsOverview?.batches ?? [], [operationsOverview]);
  const imports = useMemo(() => operationsOverview?.imports ?? [], [operationsOverview]);

  async function refreshPipelines(force = false) {
    setLoading(true);
    setMessage('');
    try {
      setPipelineOverview(await fetchAdminPipelines({ refresh: force }));
    } catch (error) {
      setMessage(error.message);
    } finally {
      setLoading(false);
    }
  }

  async function refreshOperations(force = false) {
    setLoading(true);
    setMessage('');
    try {
      setOperationsOverview(await fetchAdminOperations({ refresh: force }));
    } catch (error) {
      setMessage(error.message);
    } finally {
      setLoading(false);
    }
  }

  async function refresh() {
    if (adminSection === 'operations') {
      await refreshOperations(true);
      return;
    }
    await refreshPipelines(true);
  }

  async function selectAdminSection(section) {
    setAdminSection(section);
    setMessage('');
    if (section === 'operations' && !operationsOverview) {
      setLoading(true);
      try {
        setOperationsOverview(await fetchAdminOperations());
      } catch (error) {
        setMessage(error.message);
      } finally {
        setLoading(false);
      }
    }
  }

  async function runCollection() {
    setLoading(true);
    setMessage('');
    try {
      const result = await runMinistryCollection({
        agency,
        fillQueue,
        maxArticles,
        maxAttachmentsPerArticle: maxAttachments,
      });
      setMessage(`Run ${result.runId} ${result.status}: imported ${result.importedCount}, submitted batches ${result.submittedBatches}`);
      setOperationsOverview(await fetchAdminOperations({ refresh: true }));
    } catch (error) {
      setMessage(error.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refreshPipelines();
  }, []);

  return (
    <main className="admin-page">
      <LandingConstellation interactive={false} showGrid={false} showTexture={false} />
      <header className="admin-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={18} aria-hidden="true" />
        </button>
        <div>
          <p className="admin-kicker">Pandora RAG operations</p>
          <h1>어드민 현황</h1>
        </div>
        <div className="admin-header-actions">
          <span>{generatedAt ?? '-'}</span>
          <button type="button" onClick={refresh} disabled={loading}>
            <RefreshCw size={15} aria-hidden="true" />
            새로고침
          </button>
        </div>
      </header>

      <nav className="admin-menu-tabs" aria-label="어드민 메뉴">
        <button
          type="button"
          className={adminSection === 'pipelines' ? 'active' : ''}
          onClick={() => selectAdminSection('pipelines')}
        >
          <BarChart3 size={16} aria-hidden="true" />
          파이프라인 현황
        </button>
        <button
          type="button"
          className={adminSection === 'operations' ? 'active' : ''}
          onClick={() => selectAdminSection('operations')}
        >
          <DatabaseZap size={16} aria-hidden="true" />
          수집·Batch 관리
        </button>
      </nav>

      <section className="admin-dashboard">
        {adminSection === 'operations' && <SourceProofPanel />}
        <section className="admin-panel admin-panel-wide" aria-label="키워드 색인 갱신">
          <h2>키워드 색인 갱신</h2>
          <p>현재 활성 검색 청크의 로컬 키워드 색인을 새 버전으로 갱신합니다. 원문 재청킹·임베딩·OpenAI 전송은 하지 않습니다.</p>
          <button type="button" disabled={lexicalStarted || recoveryStarted || candidateLoading || previewLoading} onClick={refreshLexicalIndex}>
            {lexicalLoading ? '키워드 색인 갱신 중…' : '활성 청크 키워드 색인 갱신'}
          </button>
          {lexicalLoading && <p role="status">처리 중입니다. 중복 실행하지 마세요.</p>}
          {lexicalError && <p role="alert">{lexicalError}</p>}
          {lexicalResult && <pre aria-label="키워드 색인 갱신 결과">{JSON.stringify(lexicalResult, null, 2)}</pre>}
          <form onSubmit={recoverLexical}>
            <h3>중단 색인 실패 처리</h3>
            <p>실행이 종료된 BUILDING 버전만 FAILED로 표시합니다. 청크 삭제·READY 게시·재갱신은 하지 않습니다.</p>
            <label>중단 색인 버전 <input value={recoveryVersion} onChange={(event) => setRecoveryVersion(event.target.value)} disabled={recoveryStarted} required /></label>
            <label><input type="checkbox" checked={recoveryConfirmed} onChange={(event) => setRecoveryConfirmed(event.target.checked)} disabled={recoveryStarted} /> 해당 작업의 실행이 종료됐음을 확인했습니다.</label>
            <button type="submit" disabled={!recoveryConfirmed || recoveryStarted || lexicalLoading || candidateLoading || previewLoading}>중단 버전 실패 처리</button>
            {recoveryStarted && !recoveryResult && !lexicalError && <p role="status">처리 중입니다. 재시도하지 마세요.</p>}
            {recoveryResult && <p role="status">{recoveryResult}</p>}
          </form>
        </section>
        <section className="admin-panel admin-panel-wide" aria-label="문서 재구축 미리보기">
          <h2>문서 재구축 미리보기</h2>
          <p>저장된 원문의 재청킹 계획을 조회하고 손실 없는 계획의 검증 후보만 생성합니다. 임베딩·활성화는 자동 실행하지 않습니다.</p>
          <form onSubmit={previewRebuild} className="rebuild-preview-form">
            <label>문서 종류
              <select value={previewTarget} onChange={(event) => { setPreviewTarget(event.target.value); setPreviewResult(null); setCandidateResult(null); }} disabled={previewLoading || candidateLoading}>
                <option value="admrul">행정규칙</option><option value="law">법령</option>
              </select>
            </label>
            <label>문서 ID
              <input value={previewDocumentId} onChange={(event) => { setPreviewDocumentId(event.target.value); setPreviewResult(null); setCandidateResult(null); }} inputMode="numeric" pattern="[1-9][0-9]*" required disabled={previewLoading || candidateLoading} />
            </label>
            <button type="submit" disabled={previewLoading || candidateLoading}>{previewLoading ? '조회 중…' : '미리보기 조회'}</button>
          </form>
          {previewError && <p role="alert">{previewError}</p>}
          {previewResult && <pre className="rebuild-preview-result" aria-label="재구축 미리보기 결과">{JSON.stringify(previewResult, (key, value) => ['approvalToken', 'sourceUrl'].includes(key) ? undefined : value, 2)}</pre>}
          {previewResult && <button type="button" disabled={previewLoading || candidateLoading || previewResult.items?.[0]?.unexplainedLossSpanCount !== 0} onClick={createCandidate}>검증 후보 생성 (활성화하지 않음)</button>}
          {candidateLoading && <p role="status">후보 처리 중…</p>}
          {candidateResult && <pre className="rebuild-preview-result" aria-label="후보 생성 결과">{JSON.stringify(candidateResult, (key, value) => key === 'previewTokenHash' ? undefined : value, 2)}</pre>}
          {candidateResult && <button type="button" disabled={candidateLoading || candidateIndexStarted} onClick={indexCandidate}>후보만 임베딩 (OpenAI 전송, 활성화하지 않음)</button>}
          {candidateIndexResult && <pre aria-label="후보 임베딩 결과">{JSON.stringify(candidateIndexResult, null, 2)}</pre>}
          <form className="rebuild-preview-form" onSubmit={resumeIndex}>
            <label>기존 후보 버전<input value={resumeVersion} onChange={(event) => setResumeVersion(event.target.value)} inputMode="numeric" pattern="[1-9][0-9]*" required disabled={candidateLoading || candidateIndexStarted} /></label>
            <label>후보 청크 수<input value={resumeLimit} onChange={(event) => setResumeLimit(event.target.value)} inputMode="numeric" pattern="[1-9][0-9]*" required disabled={candidateLoading || candidateIndexStarted} /></label>
            <button disabled={candidateLoading || previewLoading || candidateIndexStarted}>기존 후보 임베딩 이어가기 (활성화하지 않음)</button>
          </form>
          <button type="button" disabled={candidateLoading || previewLoading || activationStarted || !previewDocumentId || !resumeVersion} onClick={activateCandidate}>명시한 후보 활성화 (서버 검증 게이트 적용)</button>
          {activationResult && <pre aria-label="후보 활성화 결과">{JSON.stringify(activationResult, null, 2)}</pre>}
        </section>
        {adminSection === 'operations' && metrics.length > 0 && (
          <section className="admin-metric-grid" aria-label="quality metrics">
            {metrics.map((metric) => (
              <article className={`admin-metric-card tone-${metric.tone ?? 'normal'}`} key={metric.key}>
                <span>{metric.label}</span>
                <strong>{formatMetricValue(metric)}</strong>
                <small>{metric.detail}</small>
              </article>
            ))}
          </section>
        )}

        {adminSection === 'pipelines' && (
          <section className="admin-panel admin-panel-wide">
            <div className="admin-panel-title">
              <BarChart3 size={18} aria-hidden="true" />
              <h2>전체 파이프라인</h2>
            </div>
            <section className="admin-summary-board" aria-label="모든 문서 현황판">
              <div className="admin-summary-heading">
                <span className="admin-status-pill tone-good">전체</span>
                <div>
                  <h3>모든 문서 현황판</h3>
                  <p>현재 검색 인덱스에 들어가는 전체 문서와 임베딩 처리 상태입니다.</p>
                </div>
              </div>
              <div className="admin-summary-stats">
                <span>
                  <small>총 문서</small>
                  <strong>{formatNumber(pipelineSummary.documents)}</strong>
                </span>
                <span>
                  <small>임베딩완료</small>
                  <strong>{formatNumber(pipelineSummary.indexedChunks)}</strong>
                </span>
                <span>
                  <small>임베딩 대기</small>
                  <strong>{formatNumber(pipelineSummary.pendingChunks)}</strong>
                </span>
                <span>
                  <small>문서군</small>
                  <strong>{formatNumber(pipelineSummary.pipelineCount)}</strong>
                </span>
              </div>
              <div className="admin-summary-groups" aria-label="문서 묶음별 현황">
                {displayPipelines.map((pipeline) => {
                  const embedProgress = progressPercent(pipeline.indexedChunks, pipeline.chunks);
                  return (
                    <article className="admin-summary-group-card" key={`summary-${pipeline.key}`}>
                      <div className="admin-summary-group-head">
                        <strong>{pipeline.pageName}</strong>
                      </div>
                      <div className="admin-summary-group-numbers">
                        <span>
                          <small>문서</small>
                          <b>{formatNumber(pipeline.documents)}</b>
                        </span>
                        <span>
                          <small>임베딩</small>
                          <b>{formatNumber(pipeline.indexedChunks)}</b>
                        </span>
                      </div>
                    </article>
                  );
                })}
              </div>
            </section>
            <div className="admin-pipeline-list">
              {displayPipelines.map((pipeline) => {
                return (
                  <article className="admin-pipeline-card" key={pipeline.key}>
                    <div className="admin-pipeline-main">
                      <span className={`admin-status-pill tone-${statusTone(pipeline.status)}`}>{pipeline.status}</span>
                      <h3>{pipeline.pageName}</h3>
                      <p>{pipeline.fetchMethod}</p>
                    </div>
                    <div className="admin-pipeline-stats">
                      <span>
                        <small>문서</small>
                        <strong>{formatNumber(pipeline.documents)}</strong>
                      </span>
                      <span>
                        <small>임베딩완료</small>
                        <strong>{formatNumber(pipeline.indexedChunks)}</strong>
                      </span>
                    </div>
                    <div className="admin-pipeline-meta">
                      <span>{pipeline.sourceType}</span>
                      <span>대기 {formatNumber(pipeline.pendingChunks)}</span>
                      <span>최근 갱신 {pipeline.lastUpdatedAt ?? '-'}</span>
                    </div>
                  </article>
                );
              })}
            </div>
            {metrics.length > 0 && (
              <section className="admin-quality-panel">
                <div className="admin-panel-title">
                  <Activity size={18} aria-hidden="true" />
                  <h2>운영 품질 점검</h2>
                </div>
                <section className="admin-metric-grid" aria-label="운영 품질 점검">
                  {metrics.map((metric) => (
                    <article className={`admin-metric-card tone-${metric.tone ?? 'normal'}`} key={metric.key}>
                      <span>{metric.label}</span>
                      <strong>{formatMetricValue(metric)}</strong>
                      <small>{metric.detail}</small>
                    </article>
                  ))}
                </section>
              </section>
            )}
          </section>
        )}

        {adminSection === 'operations' && (
          <>
          <section className="admin-grid-two">
            <div className="admin-panel">
              <div className="admin-panel-title">
                <DatabaseZap size={18} aria-hidden="true" />
                <h2>외부 수집 소스</h2>
              </div>
              <div className="admin-source-list">
                {sources.map((source) => (
                  <article className="admin-source-row" key={source.sourceKey}>
                    <div>
                      <span className={`admin-status-pill tone-${statusTone(source.status)}`}>{source.status}</span>
                      <strong>{source.agencyName}</strong>
                      <small>{source.sourceKey} · {source.sourceType}</small>
                    </div>
                    <div>
                      <span>기사 {formatNumber(source.articles)}</span>
                      <span>첨부 {formatNumber(source.attachments)}</span>
                      <span>성공 {source.lastSuccessAt ?? '-'}</span>
                    </div>
                  </article>
                ))}
                {!sources.length && <p className="admin-empty">등록된 수집 소스가 없습니다.</p>}
              </div>
            </div>

            <div className="admin-panel">
              <div className="admin-panel-title">
                <Activity size={18} aria-hidden="true" />
                <h2>OpenAI Batch</h2>
              </div>
              <div className="admin-mini-table">
                {batches.map((batch) => (
                  <div className="admin-mini-row" key={batch.batchJobId}>
                    <span className={`admin-status-pill tone-${statusTone(batch.status)}`}>{batch.status}</span>
                    <strong>{batch.target}</strong>
                    <small>{formatNumber(batch.completed)} / {formatNumber(batch.submitted)} 완료 · ingest {formatNumber(batch.ingested)}</small>
                  </div>
                ))}
                {!batches.length && <p className="admin-empty">최근 Batch 작업이 없습니다.</p>}
              </div>
            </div>
          </section>

          <section className="admin-grid-two">
            <div className="admin-panel">
              <div className="admin-panel-title">
                <FileText size={18} aria-hidden="true" />
                <h2>최근 Import</h2>
              </div>
              <div className="admin-mini-table">
                {imports.map((importJob) => (
                  <div className="admin-mini-row" key={importJob.importJobId}>
                    <span className={`admin-status-pill tone-${statusTone(importJob.status)}`}>{importJob.status}</span>
                    <strong>{importJob.documentType ?? 'all'}</strong>
                    <small>발견 {formatNumber(importJob.discovered)} · 저장 {formatNumber(importJob.imported)} · 인덱싱 {formatNumber(importJob.indexed)}</small>
                  </div>
                ))}
                {!imports.length && <p className="admin-empty">최근 Import 작업이 없습니다.</p>}
              </div>
            </div>

            <div className="admin-panel">
              <div className="admin-panel-title">
                <Layers3 size={18} aria-hidden="true" />
                <h2>수집 실행</h2>
              </div>
              <label className="admin-field">
                <span>Agency</span>
                <select value={agency} onChange={(event) => setAgency(event.target.value)}>
                  {agencyOptions.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
              <label className="admin-field">
                <span>Max articles</span>
                <input
                  type="number"
                  min="1"
                  max="100"
                  value={maxArticles}
                  onChange={(event) => setMaxArticles(Number(event.target.value))}
                />
              </label>
              <label className="admin-field">
                <span>Max attachments per article</span>
                <input
                  type="number"
                  min="1"
                  max="20"
                  value={maxAttachments}
                  onChange={(event) => setMaxAttachments(Number(event.target.value))}
                />
              </label>
              <label className="admin-toggle">
                <input
                  type="checkbox"
                  checked={fillQueue}
                  onChange={(event) => setFillQueue(event.target.checked)}
                />
                <span>Import 후 Batch 후보 제출</span>
              </label>
              <div className="admin-actions">
                <button type="button" onClick={refresh} disabled={loading}>
                  <RefreshCw size={16} aria-hidden="true" />
                  Refresh
                </button>
                <button type="button" onClick={runCollection} disabled={loading}>
                  <Send size={16} aria-hidden="true" />
                  Run collection
                </button>
              </div>
              {message && <p className="admin-message">{message}</p>}
            </div>
          </section>
          </>
        )}
      </section>
    </main>
  );
}
