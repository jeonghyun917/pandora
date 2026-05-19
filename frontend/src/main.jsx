import { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ArrowLeft,
  ArrowRight,
  BookOpenText,
  Brain,
  ChevronRight,
  Database,
  FileSearch,
  Gavel,
  KeyRound,
  Search,
} from 'lucide-react';
import './styles.css';

const lawApiMenus = [
  {
    id: 'law',
    title: '법령',
    description: '현행법령, 연혁, 영문법령, 조항호목, 법령 변경이력',
    items: ['현행법령(시행일)', '현행법령(공포일)', '법령 연혁', '영문법령', '법령 변경이력'],
  },
  {
    id: 'admin-rule',
    title: '행정규칙',
    description: '행정규칙 목록, 본문, 신구법 비교',
    items: ['행정규칙 목록', '행정규칙 본문', '행정규칙 신구법 비교'],
  },
  {
    id: 'local-law',
    title: '자치법규',
    description: '자치법규 목록, 본문, 법령 연계 정보',
    items: ['자치법규 목록', '자치법규 본문', '자치법규 기준 법령 연계'],
  },
  {
    id: 'case',
    title: '판례',
    description: '판례 목록과 판례 본문 조회',
    items: ['판례 목록', '판례 본문'],
  },
  {
    id: 'constitutional',
    title: '헌재결정례',
    description: '헌법재판소 결정례 목록과 본문',
    items: ['헌재결정례 목록', '헌재결정례 본문'],
  },
  {
    id: 'interpretation',
    title: '법령해석례',
    description: '법령해석례와 중앙부처 1차 해석',
    items: ['법령해석례 목록', '법령해석례 본문', '중앙부처 1차 해석'],
  },
  {
    id: 'appeal',
    title: '행정심판례',
    description: '행정심판례 및 특별행정심판례',
    items: ['행정심판례 목록', '행정심판례 본문', '특별행정심판례'],
  },
  {
    id: 'committee',
    title: '위원회 결정문',
    description: '개인정보보호위원회, 공정거래위원회 등 결정문',
    items: ['개인정보보호위원회', '공정거래위원회', '국민권익위원회', '노동위원회'],
  },
  {
    id: 'treaty',
    title: '조약',
    description: '조약 목록과 조약 본문',
    items: ['조약 목록', '조약 본문'],
  },
  {
    id: 'forms',
    title: '별표ㆍ서식',
    description: '법령, 행정규칙, 자치법규 별표와 서식',
    items: ['법령 별표ㆍ서식', '행정규칙 별표ㆍ서식', '자치법규 별표ㆍ서식'],
  },
  {
    id: 'terms',
    title: '법령용어',
    description: '법령 용어, 일상용어, 용어 간 관계',
    items: ['법령 용어', '일상용어', '법령용어-일상용어 연계', '조문-법령용어 연계'],
  },
  {
    id: 'ai-search',
    title: '지능형 법령검색',
    description: '지능형 법령검색 시스템 검색과 연관법령',
    items: ['지능형 법령검색 API', '연관법령 API'],
  },
];

function LuxuryBackdrop() {
  return (
    <div className="backdrop" aria-hidden="true">
      <div className="orbital orbital-a" />
      <div className="orbital orbital-b" />
      <div className="silk silk-a" />
      <div className="silk silk-b" />
      <div className="silk silk-c" />
      <div className="ribbon ribbon-a" />
      <div className="ribbon ribbon-b" />
      <div className="light-beam beam-a" />
      <div className="light-beam beam-b" />
      <div className="spotlight" />
      <div className="grain" />
    </div>
  );
}

function LandingPage({ onEnter }) {
  return (
    <main className="landing">
      <LuxuryBackdrop />
      <div className="ambient-border" aria-hidden="true" />
      <section className="hero" aria-labelledby="brand-title">
        <p className="kicker">nocturne digital house</p>
        <h1 id="brand-title">pandora</h1>
        <p className="subcopy">An entrance suspended between light, motion, and quiet luxury.</p>
        <button className="enter-button" type="button" aria-label="법령정보검색으로 들어가기" onClick={onEnter}>
          <span>enter</span>
          <ArrowRight aria-hidden="true" size={16} strokeWidth={1.5} />
        </button>
      </section>
    </main>
  );
}

function LawSearchPage({ onBack }) {
  const [activeMenu, setActiveMenu] = useState(lawApiMenus[0]);

  return (
    <main className="law-search-shell">
      <header className="law-search-header">
        <button className="icon-button" type="button" onClick={onBack} aria-label="대문페이지로 돌아가기" title="돌아가기">
          <ArrowLeft aria-hidden="true" size={18} />
        </button>
        <div>
          <p className="eyebrow">LAW OPEN DATA WORKSPACE</p>
          <h1>법령정보검색</h1>
        </div>
        <div className="header-status" aria-label="연동 준비 상태">
          <span>API 키 미연결</span>
          <KeyRound aria-hidden="true" size={16} />
        </div>
      </header>

      <section className="law-search-hero" aria-labelledby="law-search-title">
        <div>
          <p className="eyebrow">국가법령정보센터 Open API 기반</p>
          <h2 id="law-search-title">법령 데이터를 모으고, 벡터화하고, 한 번에 검색하는 공간</h2>
        </div>
        <div className="search-bar" role="search">
          <Search aria-hidden="true" size={18} />
          <input type="search" placeholder="API 키 연결 후 법령, 판례, 행정규칙을 통합검색합니다" disabled />
          <button type="button" disabled>
            검색
          </button>
        </div>
      </section>

      <section className="pipeline-grid" aria-label="향후 연동 단계">
        <article>
          <Database aria-hidden="true" size={20} />
          <strong>Open API 수집</strong>
          <span>발급받은 키로 목록과 본문 데이터를 가져옵니다.</span>
        </article>
        <article>
          <Brain aria-hidden="true" size={20} />
          <strong>로컬 벡터화</strong>
          <span>무료 임베딩 모델로 문서 조각을 벡터로 만듭니다.</span>
        </article>
        <article>
          <FileSearch aria-hidden="true" size={20} />
          <strong>통합검색</strong>
          <span>Qdrant에 저장된 벡터를 기준으로 의미 검색합니다.</span>
        </article>
      </section>

      <section className="law-browser" aria-label="법령정보 메뉴">
        <aside className="law-menu" aria-label="Open API 분류">
          <div className="law-menu-title">
            <BookOpenText aria-hidden="true" size={18} />
            <span>데이터 분류</span>
          </div>
          {lawApiMenus.map((menu) => (
            <button
              className={menu.id === activeMenu.id ? 'law-menu-item active' : 'law-menu-item'}
              key={menu.id}
              type="button"
              onClick={() => setActiveMenu(menu)}
            >
              <span>
                <strong>{menu.title}</strong>
                <small>{menu.description}</small>
              </span>
              <ChevronRight aria-hidden="true" size={16} />
            </button>
          ))}
        </aside>

        <div className="law-content">
          <div className="law-content-heading">
            <Gavel aria-hidden="true" size={22} />
            <div>
              <p className="eyebrow">선택된 Open API 분류</p>
              <h3>{activeMenu.title}</h3>
            </div>
          </div>
          <p className="law-content-description">{activeMenu.description}</p>
          <div className="law-item-list">
            {activeMenu.items.map((item) => (
              <button type="button" key={item} className="law-item-card">
                <span>{item}</span>
                <small>데이터 연동 대기</small>
              </button>
            ))}
          </div>
        </div>
      </section>
    </main>
  );
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
