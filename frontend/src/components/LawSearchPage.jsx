import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  Bot,
  Bug,
  Gavel,
  KeyRound,
  Search,
} from 'lucide-react';
import { askLawAi, askLawAiStream, fetchLawDetail, fetchRagDocumentDetail, searchLawData } from '../api/lawApi';
import { defaultSelectedMenuIds, lawApiMenus } from '../constants/lawApiMenus';
import { normalizeDetail, normalizeList } from '../domain/lawNormalize';
import { LandingConstellation } from './LandingPage';

const initialQuery = '';
const starterQuestions = [
  '개인정보 보호법은 어떤 것까지 해야 해?',
  '공공소프트웨어사업에서 단순 하드웨어 구매는 포함되나요?',
  '정보화사업 사전협의 대상은 어떻게 돼?',
  '보안성검토 대상 시스템은?',
];

// 메소드 설명: LawSearchPage 처리 흐름을 수행합니다.
export function LawSearchPage({ onBack, onDebug }) {
  const [query, setQuery] = useState(initialQuery);
  const [searchMode, setSearchMode] = useState('ai');
  const [selectedMenuIds, setSelectedMenuIds] = useState(defaultSelectedMenuIds);
  const [results, setResults] = useState([]);
  const [totalCount, setTotalCount] = useState(0);
  const [aiAnswer, setAiAnswer] = useState(null);
  const [hasSearched, setHasSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [selectedItem, setSelectedItem] = useState(null);
  const [detailPayload, setDetailPayload] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const detailPageRef = useRef(null);
  const documentViewerRef = useRef(null);
  const [viewerFocus, setViewerFocus] = useState({ text: '', pageNo: null, nonce: 0 });

  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const detail = useMemo(
    () => (detailPayload ? normalizeDetail(detailPayload, selectedItem?.title) : null),
    [detailPayload, selectedItem],
  );
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const selectedMenus = useMemo(
    () => lawApiMenus.filter((menu) => selectedMenuIds.includes(menu.id)),
    [selectedMenuIds],
  );
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const targetSectionIndex = useMemo(
    () => (detail && selectedItem ? findTargetSectionIndex(detail.sections, selectedItem) : -1),
    [detail, selectedItem],
  );
  const searchTerm = query.trim();
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const answerSourceLabel = useMemo(
    () => buildAnswerSourceLabel(results, selectedMenus[0]?.title ?? aiAnswer?.target),
    [results, selectedMenus, aiAnswer],
  );
  const targetSection = targetSectionIndex >= 0 ? detail?.sections?.[targetSectionIndex] : null;
  const targetPageNo = getTargetPageNo(selectedItem, targetSection);
  const previewUrl = detail?.previewFileUrl || detail?.originalFileUrl;
  const htmlViewerUrl = detail?.previewHtmlUrl || '';
  const canPreviewOriginal = detail?.ragDetail && isBrowserPreviewableFile(detail);
  const pdfViewerUrl = canPreviewOriginal && previewUrl
    ? buildPdfViewerUrl(
      previewUrl,
      viewerFocus.pageNo || targetPageNo,
      viewerFocus.text || selectedItem?.snippet || targetSection?.body || query,
      viewerFocus.nonce,
    )
    : '';
  const documentViewerUrl = htmlViewerUrl || pdfViewerUrl;
  const documentViewerKey = [
    htmlViewerUrl ? 'html' : 'pdf',
    detail?.originalFileName || selectedItem?.title || '',
    targetPageNo || 1,
    viewerFocus.pageNo || '',
    viewerFocus.nonce,
  ].join(':');

  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  useEffect(() => {
    if (!detail || targetSectionIndex < 0) {
      return undefined;
    }

    const frameId = window.requestAnimationFrame(() => {
      const targetElement = detailPageRef.current?.querySelector('[data-target-section="true"]');
      targetElement?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });

    return () => window.cancelAnimationFrame(frameId);
  }, [detail, targetSectionIndex]);

  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  useEffect(() => {
    if (!htmlViewerUrl || !viewerFocus.text) {
      return undefined;
    }

    const frameId = window.requestAnimationFrame(() => {
      focusHtmlPreviewText(documentViewerRef.current, viewerFocus.text);
    });

    return () => window.cancelAnimationFrame(frameId);
  }, [htmlViewerUrl, viewerFocus]);

  // 메소드 설명: loadResults 처리 흐름을 수행합니다.
  async function loadResults(nextQuery = query, menus = selectedMenus, mode = searchMode) {
    const trimmedQuery = String(nextQuery ?? '').trim();
    if (!trimmedQuery) {
      setHasSearched(false);
      setLoading(false);
      setError('');
      setResults([]);
      setTotalCount(0);
      setAiAnswer(null);
      setSelectedItem(null);
      setDetailPayload(null);
      setDetailError('');
      return;
    }

    if (menus.length === 0) {
      setHasSearched(true);
      setResults([]);
      setTotalCount(0);
      setAiAnswer(null);
      setError('검색할 데이터 분류를 하나 이상 선택하세요.');
      return;
    }

    setHasSearched(true);
    setLoading(true);
    setError('');
    setAiAnswer(null);
    setSelectedItem(null);
    setDetailPayload(null);
    setDetailError('');
    try {
      if (trimmedQuery && mode === 'ai') {
        const primaryMenu = menus[0];
        // 메소드 설명: applyAnswerPayload 처리 흐름을 수행합니다.
        const applyAnswerPayload = (answer) => {
          const groundRows = (answer?.grounds ?? []).map((ground) => normalizeGround(ground, primaryMenu));
          setAiAnswer(answer);
          setResults(groundRows);
          setTotalCount(Number(answer?.totalCnt ?? groundRows.length));
        };
        await askLawAiStream(primaryMenu.target, trimmedQuery, 8, menus.map((menu) => menu.target), {
          onGrounds: applyAnswerPayload,
          onDelta: (delta) => {
            if (!delta) {
              return;
            }
            setAiAnswer((current) => {
              if (!current) {
                return {
                  resultCode: '00',
                  resultMsg: 'STREAMING',
                  target: primaryMenu.target,
                  question: trimmedQuery,
                  answer: delta,
                  totalCnt: 0,
                  grounds: [],
                };
              }
              return {
                ...current,
                answer: `${current.answer ?? ''}${delta}`,
              };
            });
          },
          onAnswer: applyAnswerPayload,
        }).catch(async (streamError) => {
          if (streamError instanceof Error && streamError.name === 'AbortError') {
            throw streamError;
          }
          const answer = await askLawAi(primaryMenu.target, trimmedQuery, 8, menus.map((menu) => menu.target));
          applyAnswerPayload(answer);
        });
        return;
      }

      const responses = await Promise.all(
        menus.map(async (menu) => normalizeList(await searchLawData(menu, trimmedQuery, { titleOnly: true }), menu)),
      );
      setResults(responses.flatMap((response) => response.rows));
      setTotalCount(responses.reduce((sum, response) => sum + response.total, 0));
    } catch (err) {
      setResults([]);
      setTotalCount(0);
      setAiAnswer(null);
      setError(err instanceof Error ? err.message : '알 수 없는 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  }

  // 메소드 설명: toggleMenu 처리 흐름을 수행합니다.
  function toggleMenu(menuId) {
    setSelectedMenuIds((current) => {
      const nextIds = current.includes(menuId)
        ? current.filter((id) => id !== menuId)
        : [...current, menuId];
      const nextMenus = lawApiMenus.filter((menu) => nextIds.includes(menu.id));
      if (hasSearched) {
        void loadResults(query, nextMenus, searchMode);
      }
      return nextIds;
    });
  }

  function runStarterQuestion(question) {
    setQuery(question);
    void loadResults(question, selectedMenus, searchMode);
  }

  function handleSearchModeChange(nextMode) {
    setSearchMode(nextMode);
    setAiAnswer(null);
    if (hasSearched && query.trim()) {
      void loadResults(query, selectedMenus, nextMode);
    }
  }

  function handleQueryChange(event) {
    const nextQuery = event.target.value;
    setQuery(nextQuery);
    if (!nextQuery.trim() && hasSearched && !loading) {
      void loadResults('', selectedMenus);
    }
  }

  // 메소드 설명: loadDetail 처리 흐름을 수행합니다.
  async function loadDetail(item) {
    setSelectedItem(item);
    setDetailPayload(null);
    setDetailError('');
    setViewerFocus({ text: '', pageNo: null, nonce: 0 });
    if (isRagTarget(item.target)) {
      const documentId = resolveRagDocumentId(item);
      if (!documentId) {
        setDetailError('문서 상세를 조회할 documentId가 없습니다.');
        return;
      }

      setDetailLoading(true);
      try {
        setDetailPayload(await fetchRagDocumentDetail(documentId));
      } catch (err) {
        setDetailError(err instanceof Error ? err.message : '문서 상세 조회 중 오류가 발생했습니다.');
      } finally {
        setDetailLoading(false);
      }
      return;
    }

    if (!item.detailLink) {
      setDetailError('이 항목에는 상세 링크가 없습니다.');
      return;
    }

    setDetailLoading(true);
    try {
      setDetailPayload(await fetchLawDetail(item.detailLink));
    } catch (err) {
      setDetailError(err instanceof Error ? err.message : '상세 조회 중 오류가 발생했습니다.');
    } finally {
      setDetailLoading(false);
    }
  }

  // 메소드 설명: submitSearch 처리 흐름을 수행합니다.
  function submitSearch(event) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const nextQuery = String(formData.get('query') ?? query);
    setQuery(nextQuery);
    void loadResults(nextQuery, selectedMenus, searchMode);
  }

  // 메소드 설명: handleSearchKeyDown 처리 흐름을 수행합니다.
  function handleSearchKeyDown(event) {
    const isComposing = event.nativeEvent.isComposing || event.keyCode === 229;
    if (event.key === 'Enter' && !isComposing) {
      event.preventDefault();
      const nextQuery = event.currentTarget.value;
      setQuery(nextQuery);
      void loadResults(nextQuery, selectedMenus, searchMode);
    }
  }

  // 메소드 설명: focusDocumentViewer 처리 흐름을 수행합니다.
  function focusDocumentViewer(text) {
    const focusText = extractViewerFocusText(text);
    if (!focusText) {
      return;
    }
    setViewerFocus((current) => ({
      text: focusText,
      pageNo: targetPageNo,
      nonce: current.nonce + 1,
    }));
    if (htmlViewerUrl) {
      window.requestAnimationFrame(() => {
        focusHtmlPreviewText(documentViewerRef.current, focusText);
      });
    }
  }

  if (selectedItem) {
    return (
      <main className="law-search-shell detail-screen-shell">
        <LandingConstellation interactive={false} />
        <header className="law-search-header">
          <button className="icon-button" type="button" onClick={() => setSelectedItem(null)} aria-label="검색 결과로 돌아가기" title="돌아가기">
            <ArrowLeft aria-hidden="true" size={18} />
          </button>
          <div>
            <p className="eyebrow">{selectedItem.category}</p>
            <h1>{selectedItem.title}</h1>
            {(isFutureEffectiveItem(selectedItem) || detail?.meta?.length > 0) && (
              <div className="detail-header-meta">
                {isFutureEffectiveItem(selectedItem) && (
                  <span className="future-effective-badge future-effective-badge-detail">미래시행</span>
                )}
                {(detail?.meta ?? []).map((item, index) => <span key={`${item}-${index}`}>[{item}]</span>)}
              </div>
            )}
          </div>
          {detail?.contacts?.length > 0 && (
            <div className="detail-contact-meta">
              {detail.contacts.map((item, index) => <span key={`${item}-${index}`}>{item}</span>)}
            </div>
          )}
        </header>

        <section className="detail-page" aria-label="상세 정보" ref={detailPageRef}>
          {detailLoading && <p className="detail-loading">상세 정보를 불러오는 중입니다.</p>}
          {detailError && <p className="error-message">{detailError}</p>}
          {detail && documentViewerUrl && (
            <div className="pdf-detail-layout">
              <div className="pdf-viewer-shell">
                <iframe
                  key={documentViewerKey}
                  ref={documentViewerRef}
                  className={`pdf-viewer ${htmlViewerUrl ? 'html-document-viewer' : ''}`}
                  src={documentViewerUrl}
                  title={detail.originalFileName || selectedItem.title}
                  onLoad={() => {
                    if (htmlViewerUrl) {
                      focusHtmlPreviewText(
                        documentViewerRef.current,
                        viewerFocus.text || selectedItem?.snippet || targetSection?.body || '',
                      );
                    }
                  }}
                />
              </div>
              <aside className="pdf-ground-panel" aria-label="근거 문장">
                <p className="eyebrow">SOURCE PAGE {targetPageNo ? `p.${targetPageNo}` : ''}</p>
                <h2>{targetSection?.title || selectedItem.title}</h2>
                {targetSection?.body && (
                  <div className="pdf-ground-snippet">
                    {formatGroundSnippet(targetSection.body, selectedItem, focusDocumentViewer)}
                  </div>
                )}
                {!targetSection?.body && selectedItem.snippet && (
                  <div className="pdf-ground-snippet">
                    {formatGroundSnippet(selectedItem.snippet, selectedItem, focusDocumentViewer)}
                  </div>
                )}
                <a className="pdf-open-link" href={documentViewerUrl} target="_blank" rel="noreferrer">
                  원본 문서 새 창으로 열기
                </a>
              </aside>
            </div>
          )}
          {detail && !documentViewerUrl && (
            <div className="detail-section-list detail-page-sections">
              {detail.ragDetail && detail.originalFileUrl && (
                <a className="pdf-open-link detail-open-original-link" href={detail.originalFileUrl} target="_blank" rel="noreferrer">
                  원본 문서 새 창으로 열기
                </a>
              )}
              {detail.sections.map((section, index) => (
                <article
                  className={[
                    'detail-section',
                    detail.htmlDetail ? 'html-detail-section' : '',
                    section.body ? 'article-section' : 'chapter-section',
                    index === targetSectionIndex ? 'target-detail-section' : '',
                  ].filter(Boolean).join(' ')}
                  data-target-section={index === targetSectionIndex ? 'true' : undefined}
                  key={`${section.title}-${index}`}
                >
                  {section.title && <strong>{section.title}</strong>}
                  {section.body && <p>{highlightTargetSnippet(section.body, selectedItem, index === targetSectionIndex)}</p>}
                  {section.images?.length > 0 && (
                    <div className="detail-image-list">
                      {section.images.map((image, imageIndex) => (
                        <img src={image.src} alt={image.alt || `${section.title} ${imageIndex + 1}`} key={`${image.src}-${imageIndex}`} />
                      ))}
                    </div>
                  )}
                </article>
              ))}
            </div>
          )}
        </section>
      </main>
    );
  }

  return (
    <main className="law-search-shell">
      <LandingConstellation interactive={false} />
      <header className="law-search-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="처음 화면으로 돌아가기" title="돌아가기">
          <ArrowLeft aria-hidden="true" size={18} />
        </button>
        <div>
          <p className="eyebrow">LAW OPEN DATA WORKSPACE</p>
          <h1>법령 AI 검색</h1>
        </div>
        <div className="header-actions">
          <button className="debug-nav-button" type="button" onClick={onDebug}>
            <Bug aria-hidden="true" size={15} />
            <span>DEBUG</span>
          </button>
          <div className="header-status" aria-label="AI 검색 상태">
            <span>RAG ONLINE</span>
            <KeyRound aria-hidden="true" size={16} />
          </div>
        </div>
      </header>

      <section className="law-search-hero" aria-labelledby="law-search-title">
        <h2 id="law-search-title" className="visually-hidden">법령 AI 질문 검색</h2>
        <form className="search-bar" role="search" onSubmit={submitSearch}>
          <Search aria-hidden="true" size={18} />
          <div className={`search-mode-switch ${searchMode === 'db' ? 'is-db' : 'is-ai'}`} aria-label="검색 방식">
            <label className={searchMode === 'ai' ? 'search-mode-option active' : 'search-mode-option'}>
              <input
                type="radio"
                name="searchMode"
                value="ai"
                checked={searchMode === 'ai'}
                onChange={() => handleSearchModeChange('ai')}
              />
              <span>AI</span>
            </label>
            <label className={searchMode === 'db' ? 'search-mode-option active' : 'search-mode-option'}>
              <input
                type="radio"
                name="searchMode"
                value="db"
                checked={searchMode === 'db'}
                onChange={() => handleSearchModeChange('db')}
              />
              <span>DB</span>
            </label>
          </div>
          <input
            value={query}
            name="query"
            onChange={handleQueryChange}
            onKeyDown={handleSearchKeyDown}
            type="search"
            placeholder="예: 개인정보 보호법은 어떤 것까지 해야 해?"
          />
          <button type="submit" disabled={loading}>
            {searchMode === 'ai' ? '질문' : '검색'}
          </button>
        </form>
        <div className="category-filter" aria-label="검색할 데이터 분류">
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
      </section>

      <section className="law-browser single-column" aria-label="AI 답변과 근거">
        <div className="law-content">
          <div className="law-content-heading">
            {aiAnswer ? <Bot aria-hidden="true" size={22} /> : <Gavel aria-hidden="true" size={22} />}
            <div>
              <p className="eyebrow">{aiAnswer ? 'AI ANSWER' : hasSearched ? (searchMode === 'db' ? 'DB 검색 결과' : '검색 결과') : '검색 준비'}</p>
              <h3>{hasSearched ? query : '질문을 시작해 보세요'}</h3>
            </div>
          </div>

          {aiAnswer && (
            <article className="ai-answer-panel">
              <div className="ai-answer-meta">
                <span>{answerSourceLabel}</span>
              </div>
              <div className="ai-answer-body">
                {String(aiAnswer.answer ?? '').split('\n').filter(Boolean).map((line, index) => (
                  <p key={`${line}-${index}`}>{stripAnswerCitations(line)}</p>
                ))}
              </div>
            </article>
          )}

          {!hasSearched && !loading && !error && (
            <div className="search-start-panel" aria-label="검색 시작">
              <div className="starter-question-list">
                {starterQuestions.map((question) => (
                  <button
                    className="starter-question-button"
                    type="button"
                    onClick={() => runStarterQuestion(question)}
                    key={question}
                  >
                    {question}
                  </button>
                ))}
              </div>
              <div className="search-source-row">
                {selectedMenus.map((menu) => (
                  <span className={`result-source-badge result-source-badge-${normalizeSourceBadgeType(menu.target)}`} key={menu.id}>
                    {menu.title}
                  </span>
                ))}
              </div>
            </div>
          )}

          {(hasSearched || loading || error) && (
            <div className="result-summary">
              <span className={loading ? 'loading-status' : ''}>
                {loading && <span className="loading-spinner" aria-hidden="true" />}
                {loading ? '답변 생성 중입니다' : `${totalCount.toLocaleString()}건의 근거 표시`}
              </span>
              <small>{aiAnswer ? '서버가 확정한 근거 목록' : searchMode === 'db' ? '문서 제목 DB 검색' : selectedMenus.map((menu) => menu.title).join(', ') || '선택 없음'}</small>
            </div>
          )}

          {error && <p className="error-message">{error}</p>}

          {(hasSearched || results.length > 0) && (
            <div className="law-result-list">
              {results.map((item) => (
              <article className="law-result-card" key={`${item.target}-${item.id}-${item.title}`}>
                <span className="result-date">
                  <span>{item.date || '날짜 없음'}</span>
                  {isFutureEffectiveItem(item) && (
                    <span className="future-effective-badge">미래시행</span>
                  )}
                </span>
                <div className="result-main">
                  <div className="result-title-row">
                    {item.snippet && <span className="result-match-badge">{aiAnswer ? `근거 ${item.groundNumber}` : '본문 일치'}</span>}
                    {isFutureEffectiveItem(item) && (
                      <span className="future-effective-badge future-effective-badge-inline">미래시행</span>
                    )}
                    <span className={`result-source-badge result-source-badge-${normalizeSourceBadgeType(item.target)}`}>
                      {item.category}
                    </span>
                    <button className="result-title-button" type="button" onClick={() => loadDetail(item)}>
                      {highlightText(item.title, searchTerm)}
                    </button>
                  </div>
                  <div className="result-location">
                    {displayResultPosition(item) && <strong>{displayResultPosition(item)}</strong>}
                    {!displayResultPosition(item) && item.meta && <span>{item.meta}</span>}
                  </div>
                  {item.snippet && (
                    <button
                      className="result-snippet"
                      type="button"
                      onClick={() => loadDetail(item)}
                      aria-label={`${item.title} 근거 본문 상세 보기`}
                    >
                      {highlightText(cleanResultSnippet(item.snippet, item), searchTerm)}
                    </button>
                  )}
                </div>
              </article>
              ))}
              {!loading && !error && results.length === 0 && <p className="empty-message">검색 결과가 없습니다.</p>}
            </div>
          )}
        </div>
      </section>
    </main>
  );
}

// 메소드 설명: normalizeGround 처리 흐름을 수행합니다.
const futureEffectiveTargets = new Set(['law', 'admrul']);

// 메소드 설명: 법령/행정규칙 시행일이 오늘 이후인지 판정합니다.
function isFutureEffectiveItem(item) {
  if (!futureEffectiveTargets.has(item?.target)) {
    return false;
  }
  const effectiveDate = parseEffectiveDate(item?.date || item?.raw?.sourceDate || item?.raw?.source_date);
  if (!effectiveDate) {
    return false;
  }
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return effectiveDate.getTime() > today.getTime();
}

// 메소드 설명: yyyyMMdd, yyyy.MM.dd, yyyy. M. d 형태의 날짜를 파싱합니다.
function parseEffectiveDate(value) {
  const text = String(value ?? '').trim();
  if (!text) {
    return null;
  }
  const compactMatch = text.match(/^(\d{4})(\d{2})(\d{2})$/);
  const separatedMatch = text.match(/^(\d{4})\D+(\d{1,2})\D+(\d{1,2})/);
  const match = compactMatch || separatedMatch;
  if (!match) {
    return null;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (!Number.isFinite(year) || !Number.isFinite(month) || !Number.isFinite(day)) {
    return null;
  }
  if (month < 1 || month > 12 || day < 1 || day > 31) {
    return null;
  }
  const date = new Date(year, month - 1, day);
  date.setHours(0, 0, 0, 0);
  return date;
}

function normalizeGround(ground, menu) {
  return {
    id: ground.chunkId,
    documentId: ground.documentId,
    category: ground.categoryName || menu.title,
    target: ground.target || menu.target,
    title: ground.title || `근거 ${ground.number}`,
    meta: [ground.agencyName, ground.chunkTitle].filter(Boolean).join(' · '),
    date: ground.sourceDate || '',
    detailLink: isRagTarget(ground.target || menu.target) ? `rag:${ground.documentId}` : (ground.sourceUrl || ''),
    position: buildGroundPosition(ground),
    pageNo: ground.pageNo,
    snippet: ground.snippet || '',
    sourcePath: '',
    groundNumber: ground.number,
    raw: ground,
  };
}

// 메소드 설명: buildGroundPosition 처리 흐름을 수행합니다.
function buildGroundPosition(ground) {
  const pageNo = Number(ground?.pageNo);
  if (Number.isFinite(pageNo) && pageNo > 0) {
    return `원문 ${pageNo}쪽`;
  }
  return cleanDisplayPosition(ground?.chunkNo);
}

// 메소드 설명: displayResultPosition 처리 흐름을 수행합니다.
function displayResultPosition(item) {
  return cleanDisplayPosition(item?.position);
}

// 메소드 설명: cleanDisplayPosition 처리 흐름을 수행합니다.
function cleanDisplayPosition(value) {
  const text = String(value ?? '').trim();
  if (!text || /^\$[.[\]\w가-힣]+$/u.test(text)) {
    return '';
  }
  const pageMatch = text.match(/^(?:page|p\.)\s*(\d+)$/i);
  if (pageMatch) {
    return `원문 ${pageMatch[1]}쪽`;
  }
  return text
    .replace(/(?:^|\s*[ㆍ·-]\s*)\$\.pages\[\d+\]\s*/gi, '')
    .replace(/(?:^|\s*[ㆍ·-]\s*)\$\.[^\sㆍ·-]+\s*/gu, '')
    .trim();
}

// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
function isRagTarget(target) {
  return ['official_doc', 'internal_doc', 'reference_doc'].includes(target);
}

// 메소드 설명: normalizeSourceBadgeType 처리 흐름을 수행합니다.
function normalizeSourceBadgeType(target) {
  if (target === 'law') {
    return 'law';
  }
  if (target === 'admrul') {
    return 'admin';
  }
  if (target === 'official_doc') {
    return 'official';
  }
  if (target === 'internal_doc') {
    return 'internal';
  }
  if (target === 'reference_doc') {
    return 'reference';
  }
  return 'default';
}

// 메소드 설명: resolveRagDocumentId 처리 흐름을 수행합니다.
function resolveRagDocumentId(item) {
  return item?.documentId
    || parseRagDocumentId(item?.detailLink)
    || parseRagDocumentId(item?.raw?.documentId)
    || parseRagDocumentId(item?.raw?.externalId)
    || parseRagDocumentId(item?.raw?.원본식별자)
    || parseRagDocumentId(item?.id);
}

// 메소드 설명: parseRagDocumentId 처리 흐름을 수행합니다.
function parseRagDocumentId(value) {
  const match = String(value ?? '').match(/(?:rag:|db:)?(\d+)$/);
  return match?.[1] ?? '';
}

// 메소드 설명: getTargetPageNo 처리 흐름을 수행합니다.
function getTargetPageNo(item, section) {
  const direct = Number(item?.pageNo ?? section?.pageNo);
  if (Number.isFinite(direct) && direct > 0) {
    return direct;
  }
  const match = String(item?.raw?.pageNo ?? item?.raw?.chunkNo ?? item?.position ?? section?.title ?? '').match(/(?:page|p\.)\s*(\d+)/i);
  return match ? Number(match[1]) : 1;
}

// 메소드 설명: buildPdfViewerUrl 처리 흐름을 수행합니다.
function buildPdfViewerUrl(url, pageNo, searchText, revision = 0) {
  const page = Number.isFinite(Number(pageNo)) && Number(pageNo) > 0 ? Number(pageNo) : 1;
  const search = extractPdfSearchText(searchText);
  const baseUrl = revision > 0 ? appendViewerRevision(url, revision) : url;
  const params = new URLSearchParams({
    page: String(page),
    zoom: 'page-fit',
    view: 'FitH',
    pagemode: 'none',
    navpanes: '0',
  });
  if (search) {
    params.set('search', search);
  }
  return `${baseUrl}#${params.toString()}`;
}

// 메소드 설명: appendViewerRevision 처리 흐름을 수행합니다.
function appendViewerRevision(url, revision) {
  const value = String(url ?? '');
  if (!value) {
    return '';
  }
  const separator = value.includes('?') ? '&' : '?';
  return `${value}${separator}focus=${revision}`;
}

// 메소드 설명: isBrowserPreviewableFile 처리 흐름을 수행합니다.
function isBrowserPreviewableFile(detail) {
  if (detail?.previewFileUrl) {
    return true;
  }
  const fileName = String(detail?.originalFileName ?? '').toLowerCase();
  const mimeType = String(detail?.originalMimeType ?? '').toLowerCase();
  return mimeType.includes('pdf') || fileName.endsWith('.pdf');
}

// 메소드 설명: extractPdfSearchText 처리 흐름을 수행합니다.
function extractPdfSearchText(value) {
  const normalized = String(value ?? '')
    .replace(/\s+/g, ' ')
    .replace(/[^\p{L}\p{N}\s·ㆍ-]/gu, '')
    .trim();
  if (!normalized) {
    return '';
  }
  const sentence = normalized
    .split(/(?<=다)\s|(?<=음)\s|(?<=함)\s/)
    .map((item) => item.trim())
    .find((item) => item.length >= 6);
  return (sentence || normalized).slice(0, 80);
}

// 메소드 설명: buildAnswerSourceLabel 처리 흐름을 수행합니다.
function buildAnswerSourceLabel(items, fallback) {
  const targets = Array.from(new Set(
    (items ?? [])
      .map((item) => item.target)
      .filter(Boolean),
  ));
  const labels = lawApiMenus
    .filter((menu) => targets.includes(menu.target))
    .map((menu) => menu.title);
  return labels.length > 0 ? labels.join(' · ') : (fallback || '');
}

// 메소드 설명: findTargetSectionIndex 처리 흐름을 수행합니다.
function findTargetSectionIndex(sections, item) {
  const rows = Array.isArray(sections) ? sections : [];
  if (rows.length === 0 || !item) {
    return -1;
  }

  const pageNo = Number(item.pageNo ?? item.raw?.pageNo);
  if (Number.isFinite(pageNo) && pageNo > 0) {
    const pageIndex = rows.findIndex((section) => Number(section.pageNo) === pageNo);
    if (pageIndex >= 0) {
      return pageIndex;
    }
  }

  const snippet = normalizeComparableText(item.snippet);
  if (snippet) {
    const snippetIndex = rows.findIndex((section) => (
      normalizeComparableText([section.title, section.body].filter(Boolean).join(' ')).includes(snippet)
    ));
    if (snippetIndex >= 0) {
      return snippetIndex;
    }
  }

  const articleNo = findArticleNumber([
    item.raw?.chunkNo,
    item.raw?.chunkTitle,
    item.position,
    item.meta,
  ].filter(Boolean).join(' '));
  if (articleNo) {
    const articleIndex = rows.findIndex((section) => findArticleNumber(section.title) === articleNo);
    if (articleIndex >= 0) {
      return articleIndex;
    }
  }

  return -1;
}

// 메소드 설명: normalizeComparableText 처리 흐름을 수행합니다.
function normalizeComparableText(value) {
  return String(value ?? '')
    .replace(/\s+/g, ' ')
    .trim();
}

// 메소드 설명: stripAnswerCitations 처리 흐름을 수행합니다.
function stripAnswerCitations(value) {
  return String(value ?? '')
    .replace(/\s*\[(?:\d+|근거\s*\d+)(?:\s*,\s*(?:\d+|근거\s*\d+))*\]/g, '')
    .trim();
}

// 메소드 설명: cleanResultSnippet 처리 흐름을 수행합니다.
function cleanResultSnippet(value, item) {
  let text = String(value ?? '').trim();
  const title = String(item?.title ?? '').trim();
  const pageNo = String(item?.pageNo ?? '').trim();
  const chunkNo = String(item?.raw?.chunkNo ?? item?.position ?? '').trim();
  const removablePrefixes = [
    title,
    `${title}${pageNo}`,
    `${title} ${pageNo}`,
    chunkNo,
  ].filter(Boolean);

  for (const prefix of removablePrefixes) {
    if (text.startsWith(prefix)) {
      text = text.slice(prefix.length).trim();
      break;
    }
  }

  return text
    .replace(/^(?:page|p\.)\s*\d+\s*[:|ㆍ·-]?\s*/i, '')
    .replace(/^\$\.pages\[\d+\]\s*[:|ㆍ·-]?\s*/i, '')
    .replace(/^[\s|ㆍ·:：-]+/, '')
    .trim();
}

// 메소드 설명: findArticleNumber 처리 흐름을 수행합니다.
function findArticleNumber(value) {
  const match = String(value ?? '').match(/제\s*(\d+)\s*조/);
  return match?.[1] ?? '';
}

// 메소드 설명: highlightTargetSnippet 처리 흐름을 수행합니다.
function highlightTargetSnippet(body, item, isTargetSection) {
  const value = String(body ?? '');
  const snippet = String(item?.snippet ?? '').trim();
  if (!isTargetSection || !snippet) {
    return value;
  }

  const index = value.indexOf(snippet);
  if (index < 0) {
    return value;
  }

  return [
    value.slice(0, index),
    <mark className="target-snippet-mark" key="target-snippet">{value.slice(index, index + snippet.length)}</mark>,
    value.slice(index + snippet.length),
  ];
}

// 메소드 설명: formatGroundSnippet 처리 흐름을 수행합니다.
function formatGroundSnippet(value, item, onLineFocus) {
  const lines = compactFormLikeLines(splitGroundSnippet(value));
  return lines.map((line, index) => {
    const text = line.trim();
    const bullet = resolveSnippetBullet(text);
    const body = bullet ? text.slice(bullet.length).trim() : text;

    return (
      <button
        className="pdf-ground-line"
        type="button"
        onClick={() => onLineFocus?.(body || text)}
        title="왼쪽 문서에서 이 부분 찾기"
        key={`${text}-${index}`}
      >
        {bullet && <span className="pdf-ground-bullet">{bullet}</span>}
        <span>{highlightTargetSnippet(body, item, true)}</span>
      </button>
    );
  });
}

// 메소드 설명: extractViewerFocusText 처리 흐름을 수행합니다.
function extractViewerFocusText(value) {
  return String(value ?? '')
    .replace(/\s+/g, ' ')
    .replace(/^[※✓✔•→\-①②③④⑤⑥⑦⑧⑨⑩\d.)\s]+/, '')
    .trim()
    .slice(0, 140);
}

// 메소드 설명: focusHtmlPreviewText 처리 흐름을 수행합니다.
function focusHtmlPreviewText(frame, text) {
  const document = frame?.contentDocument;
  const focusText = normalizePreviewSearchText(text);
  if (!document || focusText.length < 4) {
    return false;
  }

  ensureHtmlPreviewFocusStyle(document);
  document.querySelectorAll('.pandora-preview-focus').forEach((element) => {
    element.classList.remove('pandora-preview-focus');
  });

  const candidates = [
    focusText,
    focusText.slice(0, 90),
    focusText.slice(0, 60),
    focusText.slice(0, 36),
  ].filter((value, index, values) => value.length >= 4 && values.indexOf(value) === index);

  const nodes = Array.from(document.querySelectorAll('.hx-para, .hx-table td, .hx-heading, p, td'));
  const target = nodes.find((node) => {
    const nodeText = normalizePreviewSearchText(node.textContent);
    return candidates.some((candidate) => nodeText.includes(candidate));
  });

  if (!target) {
    return false;
  }

  target.classList.add('pandora-preview-focus');
  target.scrollIntoView({ block: 'center', behavior: 'smooth' });
  return true;
}

// 메소드 설명: ensureHtmlPreviewFocusStyle 처리 흐름을 수행합니다.
function ensureHtmlPreviewFocusStyle(document) {
  if (document.getElementById('pandora-preview-focus-style')) {
    return;
  }
  const style = document.createElement('style');
  style.id = 'pandora-preview-focus-style';
  style.textContent = `
    .pandora-preview-focus {
      outline: 3px solid rgba(45, 214, 154, .95) !important;
      outline-offset: 3px !important;
      background: rgba(45, 214, 154, .18) !important;
      box-shadow: 0 0 0 7px rgba(45, 214, 154, .08) !important;
      transition: outline-color .18s ease, background .18s ease, box-shadow .18s ease;
    }
  `;
  document.head.appendChild(style);
}

// 메소드 설명: normalizePreviewSearchText 처리 흐름을 수행합니다.
function normalizePreviewSearchText(value) {
  return String(value ?? '')
    .replace(/\s+/g, '')
    .replace(/[^\p{L}\p{N}]/gu, '')
    .toLocaleLowerCase('ko-KR');
}

// 메소드 설명: compactFormLikeLines 처리 흐름을 수행합니다.
function compactFormLikeLines(lines) {
  const compacted = [];
  const fieldBuffer = [];

  // 메소드 설명: flushFields 처리 흐름을 수행합니다.
  const flushFields = () => {
    if (fieldBuffer.length === 0) {
      return;
    }
    compacted.push(fieldBuffer.join(' · '));
    fieldBuffer.length = 0;
  };

  for (const line of lines) {
    if (isFormFieldLine(line)) {
      fieldBuffer.push(line);
      if (fieldBuffer.length >= 5) {
        flushFields();
      }
      continue;
    }
    flushFields();
    compacted.push(line);
  }
  flushFields();
  return compacted;
}

// 메소드 설명: isFormFieldLine 처리 흐름을 수행합니다.
function isFormFieldLine(line) {
  const value = String(line ?? '').trim();
  if (!value || value.length > 18) {
    return false;
  }
  return /^(사\s*업\s*명|사업명|신청기관|사\s*업\s*비|사업비|백만원|대상사유|검토유형|추진|사업내용|사업개요|종합\s*검토\s*의견|검토\s*결과|신규사업|담당자|부서명|전화번호|메일|비고)$/.test(value);
}

// 메소드 설명: splitGroundSnippet 처리 흐름을 수행합니다.
function splitGroundSnippet(value) {
  return String(value ?? '')
    .replace(/\s*\|\s*/g, '\n')
    .replace(/\s*✓\s*/g, '\n✓ ')
    .replace(/\s*✔\s*/g, '\n✓ ')
    .replace(/\s*•\s*/g, '\n• ')
    .replace(/\s*※\s*/g, '\n※ ')
    .replace(/\s*→\s*/g, '\n→ ')
    .replace(/\s+-\s+/g, '\n- ')
    .replace(/([.!?])\s+(?=[가-힣A-Z0-9[(])/g, '$1\n')
    .replace(/\n{2,}/g, '\n')
    .split('\n')
    .flatMap(splitLongGroundLine)
    .map((line) => line.trim())
    .filter(Boolean);
}

// 메소드 설명: splitLongGroundLine 처리 흐름을 수행합니다.
function splitLongGroundLine(line) {
  const value = line.trim();
  if (value.length <= 70) {
    return [value];
  }

  const parts = value
    .split(/(?=\s*[①②③④⑤⑥⑦⑧⑨⑩]\s*)|(?=\s*\d+[.)]\s+)/)
    .map((part) => part.trim())
    .filter(Boolean);

  if (parts.length > 1) {
    return parts;
  }

  return value
    .split(/(?<=다\.)\s+|(?<=니다\.)\s+|(?<=함\.)\s+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

// 메소드 설명: resolveSnippetBullet 처리 흐름을 수행합니다.
function resolveSnippetBullet(value) {
  const match = value.match(/^(※|✓|✔|•|→|-|[①②③④⑤⑥⑦⑧⑨⑩]|\d+[.)]|[가-힣][.)])/);
  return match?.[1] ?? '';
}

// 메소드 설명: highlightText 처리 흐름을 수행합니다.
function highlightText(text, term) {
  const value = String(text ?? '');
  const keyword = String(term ?? '').trim();
  if (!keyword) {
    return value;
  }

  const lowerValue = value.toLocaleLowerCase('ko-KR');
  const lowerKeyword = keyword.toLocaleLowerCase('ko-KR');
  const parts = [];
  let cursor = 0;
  let index = lowerValue.indexOf(lowerKeyword);

  while (index >= 0) {
    if (index > cursor) {
      parts.push(value.slice(cursor, index));
    }
    parts.push(<mark key={`${index}-${keyword}`}>{value.slice(index, index + keyword.length)}</mark>);
    cursor = index + keyword.length;
    index = lowerValue.indexOf(lowerKeyword, cursor);
  }

  if (cursor < value.length) {
    parts.push(value.slice(cursor));
  }

  return parts;
}
