import { useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  Bug,
  CheckCircle2,
  Layers,
  Play,
  Search,
  XCircle,
} from 'lucide-react';
import { debugLawAiSearch, fetchLawAiEvaluationCases, runLawAiEvaluation } from '../api/lawApi';
import { defaultSelectedMenuIds, lawApiMenus } from '../constants/lawApiMenus';
import { LandingConstellation } from './LandingPage';

const stageLabels = {
  vector: 'Vector',
  keyword: 'Keyword',
  merged: 'Merged',
  reranked: 'Rerank',
  intent: 'Intent',
  judgeCandidates: 'Judge In',
  judge: 'Judge',
  selected: 'Selected',
};
const MAX_VISIBLE_DEBUG_ITEMS = 80;

// 메소드 설명: RagDebugPage 처리 흐름을 수행합니다.
export function RagDebugPage({ onBack }) {
  const [question, setQuestion] = useState('기타공공기관 사전협의 대상 알려줘');
  const [selectedMenuIds, setSelectedMenuIds] = useState(defaultSelectedMenuIds);
  const [debugData, setDebugData] = useState(null);
  const [activeStage, setActiveStage] = useState('selected');
  const [evaluationCases, setEvaluationCases] = useState([]);
  const [evaluation, setEvaluation] = useState(null);
  const [debugLoading, setDebugLoading] = useState(false);
  const [evalLoading, setEvalLoading] = useState(false);
  const [error, setError] = useState('');

  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const selectedMenus = useMemo(
    () => lawApiMenus.filter((menu) => selectedMenuIds.includes(menu.id)),
    [selectedMenuIds],
  );
  const activeItems = debugData?.[stageField(activeStage)] ?? [];
  const visibleActiveItems = activeItems.slice(0, MAX_VISIBLE_DEBUG_ITEMS);
  const hiddenActiveItemCount = Math.max(0, activeItems.length - visibleActiveItems.length);
  const activeStageInfo = debugData?.stages?.find((stage) => stage.name === activeStage);
  const timing = debugData?.timing;

  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  useEffect(() => {
    fetchLawAiEvaluationCases()
      .then(setEvaluationCases)
      .catch((err) => setError(err instanceof Error ? err.message : '평가셋을 불러오지 못했습니다.'));
  }, []);

  // 메소드 설명: submitDebug 처리 흐름을 수행합니다.
  async function submitDebug(event) {
    event.preventDefault();
    if (!question.trim()) {
      setError('질문을 입력해 주세요.');
      return;
    }
    if (selectedMenus.length === 0) {
      setError('검색할 데이터 분류를 하나 이상 선택하세요.');
      return;
    }
    setDebugLoading(true);
    setError('');
    try {
      const response = await debugLawAiSearch(question.trim(), selectedMenus.map((menu) => menu.target), 12);
      setDebugData(response);
      setActiveStage('selected');
    } catch (err) {
      setDebugData(null);
      setError(err instanceof Error ? err.message : '검색 진단에 실패했습니다.');
    } finally {
      setDebugLoading(false);
    }
  }

  // 메소드 설명: runEvaluation 처리 흐름을 수행합니다.
  async function runEvaluation() {
    setEvalLoading(true);
    setError('');
    try {
      setEvaluation(await runLawAiEvaluation(evaluationCases));
    } catch (err) {
      setEvaluation(null);
      setError(err instanceof Error ? err.message : '평가 실행에 실패했습니다.');
    } finally {
      setEvalLoading(false);
    }
  }

  // 메소드 설명: toggleMenu 처리 흐름을 수행합니다.
  function toggleMenu(menuId) {
    setSelectedMenuIds((current) => (
      current.includes(menuId) ? current.filter((id) => id !== menuId) : [...current, menuId]
    ));
  }

  return (
    <main className="law-search-shell rag-debug-shell">
      <LandingConstellation interactive={false} showGrid={false} showTexture={false} />
      <header className="law-search-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="검색 화면으로 돌아가기" title="돌아가기">
          <ArrowLeft aria-hidden="true" size={18} />
        </button>
        <div>
          <p className="eyebrow">RAG DEBUG WORKSPACE</p>
          <h1>검색 진단</h1>
        </div>
        <div className="header-status">
          <span>TRACE MODE</span>
          <Bug aria-hidden="true" size={16} />
        </div>
      </header>

      <section className="debug-workbench">
        <form className="debug-query-panel" onSubmit={submitDebug}>
          <div className="debug-query-input">
            <Search aria-hidden="true" size={18} />
            <input
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="예: 정보화사업 사전협의 대상도 알수있어?"
            />
            <button type="submit" disabled={debugLoading}>
              <Play aria-hidden="true" size={16} />
              <span>{debugLoading ? '진단 중' : '진단'}</span>
            </button>
          </div>
          <div className="category-filter debug-category-filter" aria-label="진단할 데이터 분류">
            {lawApiMenus.map((menu) => (
              <label className="category-checkbox" key={menu.id}>
                <input
                  type="checkbox"
                  checked={selectedMenuIds.includes(menu.id)}
                  onChange={() => toggleMenu(menu.id)}
                />
                <span>{menu.title}</span>
              </label>
            ))}
          </div>
        </form>

        {error && <p className="error-message">{error}</p>}

        <section className="debug-grid" aria-label="검색 진단 결과">
          <div className="debug-panel debug-stage-panel">
            <div className="debug-panel-heading">
              <Layers aria-hidden="true" size={18} />
              <h2>검색 단계</h2>
            </div>
            <div className="debug-stage-list">
              {(debugData?.stages ?? []).map((stage) => (
                <button
                  className={activeStage === stage.name ? 'debug-stage active' : 'debug-stage'}
                  type="button"
                  onClick={() => setActiveStage(stage.name)}
                  key={stage.name}
                >
                  <span>
                    <strong>{stageLabels[stage.name] ?? stage.name}</strong>
                    <small>{stage.description}</small>
                  </span>
                  <strong>{stage.count}</strong>
                </button>
              ))}
              {!debugData && <p className="empty-message">진단 결과가 없습니다.</p>}
            </div>
            {debugData?.lexicalKeywords?.length > 0 && (
              <div className="debug-keyword-list">
                {debugData.lexicalKeywords.map((keyword) => (
                  <span key={keyword}>{keyword}</span>
                ))}
              </div>
            )}
            {debugData?.focusedKeywords?.length > 0 && (
              <div className="debug-query-block">
                <strong>Focused keywords</strong>
                <div className="debug-keyword-list compact">
                  {debugData.focusedKeywords.map((keyword) => (
                    <span key={keyword}>{keyword}</span>
                  ))}
                </div>
              </div>
            )}
            {debugData?.expandedQueries?.length > 0 && (
              <div className="debug-query-block">
                <strong>Expanded queries</strong>
                <ul>
                  {debugData.expandedQueries.map((query) => (
                    <li key={query}>{query}</li>
                  ))}
                </ul>
              </div>
            )}
            {debugData?.clarificationQuestions?.length > 0 && (
              <div className="debug-query-block">
                <strong>Clarification candidates</strong>
                <ul>
                  {debugData.clarificationQuestions.map((question) => (
                    <li key={question}>{question}</li>
                  ))}
                </ul>
              </div>
            )}
            {debugData?.message && (
              <p className="debug-diagnostic-message">{debugData.message}</p>
            )}
            {timing && (
              <div className="debug-timing-grid" aria-label="검색 소요 시간">
                <span><strong>{formatMillis(timing.totalMs)}</strong><small>total</small></span>
                <span><strong>{formatMillis(timing.embeddingMs)}</strong><small>embedding</small></span>
                <span><strong>{formatMillis(timing.qdrantMs)}</strong><small>qdrant</small></span>
                <span><strong>{formatMillis(timing.dbMs)}</strong><small>db</small></span>
                <span><strong>{formatMillis(timing.judgeMs)}</strong><small>judge</small></span>
                <span><strong>{formatMillis(timing.answerMs)}</strong><small>answer</small></span>
              </div>
            )}
          </div>

          <div className="debug-panel debug-result-panel">
            <div className="debug-panel-heading">
              <Bug aria-hidden="true" size={18} />
              <div>
                <h2>{stageLabels[activeStage] ?? activeStage}</h2>
                {activeStageInfo && <p>{activeStageInfo.description}</p>}
              </div>
              {activeItems.length > 0 && (
                <span className="debug-visible-count">
                  {hiddenActiveItemCount > 0
                    ? `상위 ${visibleActiveItems.length} / 전체 ${activeItems.length}`
                    : `${activeItems.length}건`}
                </span>
              )}
            </div>
            <div className="debug-item-list">
              {visibleActiveItems.map((item) => (
                <article className={item.selected ? 'debug-item selected' : 'debug-item'} key={`${item.target}-${item.chunkId}`}>
                  <div className="debug-item-rank">{item.rank}</div>
                  <div className="debug-item-body">
                    <div className="debug-item-title">
                      <span>{item.categoryName || item.target}</span>
                      <span className="debug-target-badge">{item.target}</span>
                      <strong>{item.title}</strong>
                    </div>
                    <div className="debug-item-meta">
                      <span>{item.chunkNo || '위치 없음'}</span>
                      <span>vec {formatScore(item.vectorScore)}</span>
                      <span>key {formatScore(item.keywordScore)}</span>
                      <span>meta {formatScore(item.metadataScore)}</span>
                      <span>hybrid {formatScore(item.combinedScore)}</span>
                      <span>final {formatScore(item.finalScore)}</span>
                    </div>
                    {item.matchedTerms?.length > 0 && (
                      <div className="debug-keyword-list compact">
                        {item.matchedTerms.map((term) => <span key={term}>{term}</span>)}
                      </div>
                    )}
                    <p>{item.snippet}</p>
                  </div>
                </article>
              ))}
              {hiddenActiveItemCount > 0 && (
                <p className="empty-message">
                  화면 성능을 위해 나머지 {hiddenActiveItemCount}건은 숨겼습니다. 단계 count와 API 응답에는 전체 후보가 유지됩니다.
                </p>
              )}
              {debugData && activeItems.length === 0 && <p className="empty-message">이 단계에 후보가 없습니다.</p>}
            </div>
          </div>
        </section>

        <section className="debug-panel debug-eval-panel" aria-label="평가셋">
          <div className="debug-panel-heading">
            <CheckCircle2 aria-hidden="true" size={18} />
            <h2>평가셋</h2>
            <button className="refresh-button" type="button" onClick={runEvaluation} disabled={evalLoading}>
              {evalLoading ? '실행 중' : '평가 실행'}
            </button>
          </div>
          {evaluation && (
            <div className="debug-eval-summary">
              <span>total {evaluation.total}</span>
              <span>passed {evaluation.passed}</span>
              <span>failed {evaluation.failed}</span>
              <span>gate {evaluation.gatePassed ? 'PASS' : 'FAIL'}</span>
              <span>rate {Math.round((evaluation.passRate ?? 0) * 100)}%</span>
            </div>
          )}
          <div className="debug-eval-list">
            {(evaluation?.results ?? evaluationCases).map((item) => {
              const status = item.passed === true ? 'pass' : item.passed === false ? 'fail' : 'pending';
              return (
              <article className="debug-eval-row" key={item.id}>
                <div className={`debug-eval-status ${status}`}>
                  {status === 'fail' && <XCircle aria-hidden="true" size={16} />}
                  {status === 'pass' && <CheckCircle2 aria-hidden="true" size={16} />}
                  {status === 'pending' && <Layers aria-hidden="true" size={16} />}
                </div>
                <div>
                  <strong>{item.question}</strong>
                  <p>{formatEvalTerms(item)}</p>
                </div>
              </article>
              );
            })}
          </div>
        </section>
      </section>
    </main>
  );
}

// 메소드 설명: stageField 처리 흐름을 수행합니다.
function stageField(stage) {
  if (stage === 'keyword') {
    return 'lexicalHits';
  }
  if (stage === 'intent') {
    return 'intentFiltered';
  }
  if (stage === 'judge') {
    return 'judged';
  }
  return stage === 'vector' ? 'vectorHits' : stage;
}

// 메소드 설명: formatScore 처리 흐름을 수행합니다.
function formatScore(value) {
  const score = Number(value);
  return Number.isFinite(score) ? score.toFixed(3) : '0.000';
}

// 메소드 설명: formatMillis 처리 흐름을 수행합니다.
function formatMillis(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '0ms';
  }
  return `${Math.max(0, Math.round(number)).toLocaleString()}ms`;
}

// 메소드 설명: formatEvalTerms 처리 흐름을 수행합니다.
function formatEvalTerms(item) {
  if (Array.isArray(item.matchedTerms)) {
    const matched = item.matchedTerms.length ? item.matchedTerms.join(', ') : '없음';
    const missing = item.missingTerms?.length ? item.missingTerms.join(', ') : '없음';
    const topMatched = item.topMatchedTerms?.length ? item.topMatchedTerms.join(', ') : '없음';
    return `matched: ${matched} / missing: ${missing} / top: ${topMatched}`;
  }
  return (item.expectedTerms ?? []).join(', ');
}
