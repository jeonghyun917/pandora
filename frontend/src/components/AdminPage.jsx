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
import { fetchAdminOperations, fetchAdminPipelines, runMinistryCollection } from '../api/lawApi';
import { LandingConstellation } from './LandingPage';

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
