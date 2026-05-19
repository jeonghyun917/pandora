import { StrictMode, useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ArrowLeft,
  ArrowRight,
  Gavel,
  KeyRound,
  Search,
  X,
} from 'lucide-react';
import './styles.css';

const lawApiMenus = [
  { id: 'law', target: 'law', title: '법령', description: '현행 법령과 연혁 법령 목록을 조회합니다.', defaultQuery: '개인정보' },
  { id: 'case', target: 'prec', title: '판례', description: '대법원과 하급심 판례 목록을 조회합니다.', defaultQuery: '손해배상' },
  { id: 'admin-rule', target: 'admrul', title: '행정규칙', description: '훈령, 예규, 고시 등 행정규칙 목록을 조회합니다.', defaultQuery: '개인정보' },
  { id: 'local-law', target: 'ordin', title: '자치법규', description: '지방자치단체 조례와 규칙 목록을 조회합니다.', defaultQuery: '주차장' },
  { id: 'constitutional', target: 'detc', title: '헌재결정례', description: '헌법재판소 결정례 목록을 조회합니다.', defaultQuery: '위헌' },
  { id: 'interpretation', target: 'expc', title: '법령해석례', description: '법제처 법령해석례 목록을 조회합니다.', defaultQuery: '건축법' },
  { id: 'appeal', target: 'decc', title: '행정심판례', description: '중앙행정심판위원회 재결례 목록을 조회합니다.', defaultQuery: '영업정지' },
  { id: 'treaty', target: 'trty', title: '조약', description: '대한민국 조약 목록을 조회합니다.', defaultQuery: '무역' },
  { id: 'law-forms', target: 'licbyl', title: '법령 별표서식', description: '법령에 포함된 별표와 서식 목록을 조회합니다.', defaultQuery: '신청서' },
  { id: 'admin-rule-forms', target: 'admbyl', title: '행정규칙 별표서식', description: '행정규칙의 별표와 서식 목록을 조회합니다.', defaultQuery: '서식' },
  { id: 'local-law-forms', target: 'ordinbyl', title: '자치법규 별표서식', description: '자치법규의 별표와 서식 목록을 조회합니다.', defaultQuery: '서식' },
  { id: 'linked-local-law', target: 'lnkLs', title: '법령-자치법규 연계', description: '법령과 연결된 자치법규 정보를 조회합니다.', defaultQuery: '도로교통법' },
];

const reservedSearchKeys = new Set(['resultCode', 'resultMsg', 'target', '키워드', 'section', 'totalCnt', 'page', 'numOfRows']);
const defaultSelectedMenuIds = lawApiMenus.map((menu) => menu.id);
const initialQuery = '';

function normalizeList(payload, menu) {
  const target = menu.target;
  const searchRootKey = Object.keys(payload ?? {}).find((key) => {
    const value = payload[key];
    return value && typeof value === 'object' && ('resultCode' in value || 'resultMsg' in value || 'totalCnt' in value);
  });
  const root = searchRootKey ? payload[searchRootKey] : payload;
  if (!root) {
    return { rows: [], total: 0, message: '응답 데이터가 비어 있습니다.' };
  }

  const listKey = Object.keys(root).find((key) => !reservedSearchKeys.has(key) && (Array.isArray(root[key]) || typeof root[key] === 'object'));
  const rawRows = listKey ? root[listKey] : [];
  const rows = (Array.isArray(rawRows) ? rawRows : [rawRows]).filter(Boolean);

  return {
    rows: rows.map((row, index) => normalizeRow(row, target, index, menu)),
    total: Number(root.totalCnt ?? rows.length),
    message: root.resultMsg ?? 'success',
  };
}

function normalizeRow(row, target, index, menu) {
  const title =
    row.법령명한글 ??
    row.판례명 ??
    row.사건명 ??
    row.행정규칙명 ??
    row.자치법규명 ??
    row.조약명 ??
    row.별표명 ??
    row.별표서식명 ??
    row.관련법령명 ??
    row.관련법령명한글 ??
    row.관련행정규칙명 ??
    row.관련자치법규명 ??
    row.안건명 ??
    row.제목 ??
    `${target} 항목 ${index + 1}`;
  const meta =
    row.소관부처명 ??
    row.법원명 ??
    row.자치단체명 ??
    row.지자체기관명 ??
    row.전체기관명 ??
    row.회신기관명 ??
    row.질의기관명 ??
    row.재결청 ??
    row.처분청 ??
    row.조약구분명 ??
    row.행정규칙종류 ??
    row.자치법규종류 ??
    row.공포번호 ??
    row.사건번호 ??
    row.안건번호 ??
    row.조약번호 ??
    row.발령번호 ??
    row.구분 ??
    '국가법령정보센터';
  const date =
    row.시행일자 ??
    row.공포일자 ??
    row.선고일자 ??
    row.발령일자 ??
    row.회신일자 ??
    row.의결일자 ??
    row.종국일자 ??
    row.처분일자 ??
    row.서명일자 ??
    row.발효일자 ??
    row.자치법규시행일자 ??
    row.체결일자 ??
    '';
  const id =
    row.법령일련번호 ??
    row.판례일련번호 ??
    row.행정규칙일련번호 ??
    row.자치법규일련번호 ??
    row.법령해석례일련번호 ??
    row.행정심판재결례일련번호 ??
    row.헌재결정례일련번호 ??
    row.조약일련번호 ??
    row.별표일련번호 ??
    row.ID ??
    row.id ??
    `${target}-${index}`;

  return {
    id,
    category: menu.title,
    target,
    title: sanitizeText(title),
    meta: sanitizeText(meta),
    date: formatDate(String(date)),
    detailLink: findDetailLink(row, target),
    raw: row,
  };
}

function findDetailLink(row, target) {
  const linkKey = Object.keys(row).find((key) => key.includes('상세링크') || key.includes('파일링크') || key.toLowerCase().includes('link'));
  if (linkKey) {
    return row[linkKey];
  }
  if ((target === 'law' || target === 'lnkLs') && row.법령일련번호) {
    return `/DRF/lawService.do?OC=***&target=law&MST=${row.법령일련번호}&type=HTML&mobileYn=`;
  }
  return '';
}

function sanitizeText(value) {
  return String(value ?? '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
}

function normalizeDetail(payload, fallbackTitle) {
  if (payload?.unsupported) {
    return {
      title: fallbackTitle,
      meta: ['원문 보기 필요'],
      sections: [{
        title: '원문',
        body: '국가법령정보센터 원문을 아래 영역에 표시합니다.',
      }],
      unsupported: true,
    };
  }

  const rootKey = Object.keys(payload ?? {})[0];
  const root = rootKey ? payload[rootKey] : payload;
  const base = root?.기본정보 ?? root?.판례정보 ?? root?.행정규칙기본정보 ?? root?.자치법규기본정보 ?? root ?? {};
  const title =
    base.법령명_한글 ??
    base.법령명한글 ??
    base.판례명 ??
    base.사건명 ??
    base.행정규칙명 ??
    base.자치법규명 ??
    base.조약명 ??
    fallbackTitle;
  const meta = [
    getText(base.소관부처),
    base.법종구분?.content,
    base.공포번호,
    base.사건번호,
    base.법원명,
    formatDate(String(base.시행일자 ?? base.공포일자 ?? base.선고일자 ?? '')),
  ].filter(Boolean);

  return {
    title,
    meta,
    sections: collectDetailSections(root),
  };
}

function collectDetailSections(root) {
  const articleUnits = asArray(root?.조문?.조문단위)
    .filter((unit) => unit.조문여부 === '조문' && unit.조문내용)
    .slice(0, 12)
    .map((unit) => ({
      title: unit.조문제목 ? `제${unit.조문번호}조 ${unit.조문제목}` : `제${unit.조문번호}조`,
      body: [unit.조문내용, ...asArray(unit.항).map((항) => 항.항내용)].filter(Boolean).join('\n'),
    }));
  if (articleUnits.length > 0) {
    return articleUnits;
  }

  const caseBody = root?.판례내용 ?? root?.판시사항 ?? root?.판결요지 ?? root?.이유;
  if (caseBody) {
    return [{ title: '본문', body: getText(caseBody) }];
  }

  const textValue = findFirstLongText(root);
  return textValue ? [{ title: '상세 내용', body: textValue }] : [{ title: '상세 데이터', body: '표시할 본문 항목을 찾지 못했습니다.' }];
}

function findFirstLongText(value) {
  if (typeof value === 'string' && value.length > 40) {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(findFirstLongText).find(Boolean);
  }
  if (value && typeof value === 'object') {
    return Object.values(value).map(findFirstLongText).find(Boolean);
  }
  return '';
}

function asArray(value) {
  if (!value) {
    return [];
  }
  return Array.isArray(value) ? value : [value];
}

function getText(value) {
  if (!value) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(getText).filter(Boolean).join('\n');
  }
  if (typeof value === 'object') {
    return value.content ?? Object.values(value).map(getText).filter(Boolean).join('\n');
  }
  return String(value);
}

function formatDate(value) {
  if (!/^\d{8}$/.test(value)) {
    return value;
  }
  return `${value.slice(0, 4)}.${value.slice(4, 6)}.${value.slice(6, 8)}`;
}

function LandingPage({ onEnter }) {
  return (
    <main className="landing">
      <section className="hero" aria-labelledby="brand-title">
        <p className="kicker">law open data workspace</p>
        <h1 id="brand-title">pandora</h1>
        <p className="subcopy">국가법령정보센터 데이터를 안전하게 연결하는 법령 검색 작업공간</p>
        <button className="enter-button" type="button" onClick={onEnter}>
          <span>시작하기</span>
          <ArrowRight aria-hidden="true" size={16} strokeWidth={1.5} />
        </button>
      </section>
    </main>
  );
}

function LawSearchPage({ onBack }) {
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
        menus.map(async (menu) => {
          const params = new URLSearchParams({ target: menu.target, query: nextQuery || '*', display: '10' });
          const response = await fetch(`/api/law-data/search?${params.toString()}`);
          if (!response.ok) {
            const problem = await response.json().catch(() => null);
            throw new Error(problem?.message ?? `${menu.title} 조회에 실패했습니다.`);
          }
          const data = await readJsonResponse(response);
          return normalizeList(data, menu);
        }),
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
      const params = new URLSearchParams({ link: item.detailLink });
      const response = await fetch(`/api/law-data/detail?${params.toString()}`);
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new Error(problem?.message ?? '상세 정보를 불러오지 못했습니다.');
      }
      setDetailPayload(await readJsonResponse(response));
    } catch (err) {
      setDetailError(err instanceof Error ? err.message : '상세 조회 중 오류가 발생했습니다.');
    } finally {
      setDetailLoading(false);
    }
  }

  function getProxyUrl(link) {
    return `/api/law-data/proxy?${new URLSearchParams({ link }).toString()}`;
  }

  function submitSearch(event) {
    event.preventDefault();
    void loadResults(query, selectedMenus);
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
          </div>
          <button type="button" className="detail-close" onClick={() => setSelectedItem(null)} aria-label="상세 닫기">
            <X aria-hidden="true" size={16} />
          </button>
        </header>

        <section className="detail-page" aria-label="상세 정보">
          {detailLoading && <p className="detail-loading">상세 정보를 불러오는 중입니다.</p>}
          {detailError && <p className="error-message">{detailError}</p>}
          {detail && (
            <>
              {detail.meta.length > 0 && <p className="detail-meta">{detail.meta.join(' · ')}</p>}
              {detail.unsupported && selectedItem.detailLink ? (
                <div className="source-frame-wrap">
                  <iframe className="source-frame" title={`${selectedItem.title} 원문`} src={getProxyUrl(selectedItem.detailLink)} />
                </div>
              ) : (
                <div className="detail-section-list detail-page-sections">
                  {detail.sections.map((section, index) => (
                    <article className="detail-section" key={`${section.title}-${index}`}>
                      <strong>{section.title}</strong>
                      <p>{section.body}</p>
                    </article>
                  ))}
                </div>
              )}
            </>
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
          <input value={query} onChange={(event) => setQuery(event.target.value)} type="search" placeholder="법령, 규칙, 지침을 검색하세요" />
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

async function readJsonResponse(response) {
  const text = await response.text();
  if (!text.trim()) {
    return {};
  }
  return JSON.parse(text);
}

function App() {
  const [page, setPage] = useState('landing');

  if (page === 'law-search') {
    return <LawSearchPage onBack={() => setPage('landing')} />;
  }

  return <LandingPage onEnter={() => setPage('law-search')} />;
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
