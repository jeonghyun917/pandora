import { useState } from 'react';
import { LandingPage } from './components/LandingPage';
import { LawSearchPage } from './components/LawSearchPage';
import { RagDebugPage } from './components/RagDebugPage';

// 메소드 설명: App 처리 흐름을 수행합니다.
export function App() {
  const [page, setPage] = useState('landing');

  if (page === 'law-search') {
    return <LawSearchPage onBack={() => setPage('landing')} onDebug={() => setPage('rag-debug')} />;
  }

  if (page === 'rag-debug') {
    return <RagDebugPage onBack={() => setPage('law-search')} />;
  }

  return <LandingPage onEnter={() => setPage('law-search')} />;
}
