import { useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  Gavel,
  KeyRound,
  Search,
  X,
} from 'lucide-react';
import { fetchLawDetail, searchLawData } from '../api/lawApi';
import { defaultSelectedMenuIds, lawApiMenus } from '../constants/lawApiMenus';
import { normalizeDetail, normalizeList } from '../domain/lawNormalize';

const initialQuery = '';

export function LawSearchPage({ onBack }) {
  const [query, setQuery] = useState(initialQuery);
  const [selectedMenuIds, setSelectedMenuIds] = useState(defaultSelectedMenuIds);
  const [results, setResults] = useState([]);
  const [totalCount, setTotalCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [selectedItem, setSelectedItem] = useState(null);
  const [detailPayload, setDetailPayload] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');

  const detail = useMemo(
    () => (detailPayload ? normalizeDetail(detailPayload, selectedItem?.title) : null),
    [detailPayload, selectedItem],
  );
  const selectedMenus = useMemo(
    () => lawApiMenus.filter((menu) => selectedMenuIds.includes(menu.id)),
    [selectedMenuIds],
  );

  useEffect(() => {
    void loadResults(initialQuery, selectedMenus);
  }, []);

  async function loadResults(nextQuery = query, menus = selectedMenus) {
    if (menus.length === 0) {
      setResults([]);
      setTotalCount(0);
      setError('검색할 데이터 분류를 하나 이상 선택하세요.');
      return;
    }

    setLoading(true);
    setError('');
    setSelectedItem(null);
    setDetailPayload(null);
    setDetailError('');
    try {
      const responses = await Promise.all(
        menus.map(async (menu) => normalizeList(await searchLawData(menu, nextQuery), menu)),
      );
      setResults(responses.flatMap((response) => response.rows));
      setTotalCount(responses.reduce((sum, response) => sum + response.total, 0));
    } catch (err) {
      setResults([]);
      setTotalCount(0);
      setError(err instanceof Error ? err.message : '알 수 없는 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  }

  function toggleMenu(menuId) {
    setSelectedMenuIds((current) => (
      current.includes(menuId)
        ? current.filter((id) => id !== menuId)
        : [...current, menuId]
    ));
  }

  async function loadDetail(item) {
    setSelectedItem(item);
    setDetailPayload(null);
    setDetailError('');
    if (!item.detailLink) {
      setDetailError('이 항목에는 상세링크가 없습니다.');
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

  function submitSearch(event) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const nextQuery = String(formData.get('query') ?? query);
    setQuery(nextQuery);
    void loadResults(nextQuery, selectedMenus);
  }

  function handleSearchKeyDown(event) {
    const isComposing = event.nativeEvent.isComposing || event.keyCode === 229;
    if (event.key === 'Enter' && !isComposing) {
      event.preventDefault();
      const nextQuery = event.currentTarget.value;
      setQuery(nextQuery);
      void loadResults(nextQuery, selectedMenus);
    }
  }

  if (selectedItem) {
    return (
      <main className="law-search-shell detail-screen-shell">
        <header className="law-search-header">
          <button className="icon-button" type="button" onClick={() => setSelectedItem(null)} aria-label="검색 결과로 돌아가기" title="돌아가기">
            <ArrowLeft aria-hidden="true" size={18} />
          </button>
          <div>
            <p className="eyebrow">{selectedItem.category}</p>
            <h1>{selectedItem.title}</h1>
            {detail?.meta?.length > 0 && (
              <div className="detail-header-meta">
                {detail.meta.map((item, index) => <span key={`${item}-${index}`}>[{item}]</span>)}
              </div>
            )}
          </div>
          {detail?.contacts?.length > 0 && (
            <div className="detail-contact-meta">
              {detail.contacts.map((item, index) => <span key={`${item}-${index}`}>{item}</span>)}
            </div>
          )}
          <button type="button" className="detail-close" onClick={() => setSelectedItem(null)} aria-label="상세 닫기">
            <X aria-hidden="true" size={16} />
          </button>
        </header>

        <section className="detail-page" aria-label="상세 정보">
          {detailLoading && <p className="detail-loading">상세 정보를 불러오는 중입니다.</p>}
          {detailError && <p className="error-message">{detailError}</p>}
          {detail && (
            <div className="detail-section-list detail-page-sections">
              {detail.sections.map((section, index) => (
                <article
                  className={[
                    'detail-section',
                    detail.htmlDetail ? 'html-detail-section' : '',
                    section.body ? 'article-section' : 'chapter-section',
                  ].filter(Boolean).join(' ')}
                  key={`${section.title}-${index}`}
                >
                  {section.title && <strong>{section.title}</strong>}
                  {section.body && <p>{section.body}</p>}
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
      <header className="law-search-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="처음 화면으로 돌아가기" title="돌아가기">
          <ArrowLeft aria-hidden="true" size={18} />
        </button>
        <div>
          <p className="eyebrow">LAW OPEN DATA WORKSPACE</p>
          <h1>법령정보 검색</h1>
        </div>
        <div className="header-status" aria-label="API 연동 상태">
          <span>백엔드 보안 프록시 연결</span>
          <KeyRound aria-hidden="true" size={16} />
        </div>
      </header>

      <section className="law-search-hero" aria-labelledby="law-search-title">
        <h2 id="law-search-title" className="visually-hidden">법령 검색</h2>
        <form className="search-bar" role="search" onSubmit={submitSearch}>
          <Search aria-hidden="true" size={18} />
          <input
            value={query}
            name="query"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={handleSearchKeyDown}
            type="search"
            placeholder="법령, 규칙, 지침을 검색하세요"
          />
          <button type="submit" disabled={loading}>
            검색
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

      <section className="law-browser single-column" aria-label="검색 결과">
        <div className="law-content">
          <div className="law-content-heading">
            <Gavel aria-hidden="true" size={22} />
            <div>
              <p className="eyebrow">검색 결과</p>
              <h3>{query || '전체 검색'}</h3>
            </div>
          </div>

          <div className="result-summary">
            <span>{loading ? '조회 중입니다' : `${totalCount.toLocaleString()}건 중 ${results.length.toLocaleString()}건 표시`}</span>
            <small>{selectedMenus.map((menu) => menu.title).join(', ') || '선택 없음'}</small>
          </div>

          {error && <p className="error-message">{error}</p>}

          <div className="law-result-list">
            {results.map((item) => (
              <article className="law-result-card" key={`${item.target}-${item.id}-${item.title}`}>
                <span className="result-date">{item.date || '날짜 없음'}</span>
                <button className="result-title-button" type="button" onClick={() => loadDetail(item)}>
                  {item.title}
                </button>
                <small>{item.category} · {item.meta}</small>
              </article>
            ))}
            {!loading && !error && results.length === 0 && <p className="empty-message">검색 결과가 없습니다.</p>}
          </div>
        </div>
      </section>
    </main>
  );
}
