import { useState } from 'react';
import { LandingPage } from './components/LandingPage';
import { LawSearchPage } from './components/LawSearchPage';

export function App() {
  const [page, setPage] = useState('landing');

  if (page === 'law-search') {
    return <LawSearchPage onBack={() => setPage('landing')} />;
  }

  return <LandingPage onEnter={() => setPage('law-search')} />;
}
