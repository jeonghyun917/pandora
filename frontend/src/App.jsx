import { useEffect, useState } from 'react';
import { fetchAuthStatus, logoutAdmin } from './api/lawApi';
import { LandingPage } from './components/LandingPage';
import { LawSearchPage } from './components/LawSearchPage';
import { RagDebugPage } from './components/RagDebugPage';
import { AdminPage } from './components/AdminPage';
import { LoginPage } from './components/LoginPage';

// 메소드 설명: App 처리 흐름을 수행합니다.
export function App() {
  const [page, setPage] = useState('landing');
  const [authStatus, setAuthStatus] = useState(null);

  useEffect(() => {
    let cancelled = false;
    fetchAuthStatus()
      .then((result) => {
        if (!cancelled) {
          setAuthStatus(result);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setAuthStatus({ authenticated: false });
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleLogout() {
    await logoutAdmin();
    setAuthStatus({ authenticated: false });
    setPage('landing');
  }

  if (authStatus === null) {
    return (
      <main className="auth-page">
        <section className="auth-loading">Checking session</section>
      </main>
    );
  }

  if (!authStatus.authenticated) {
    return <LoginPage onAuthenticated={setAuthStatus} />;
  }

  if (page === 'law-search') {
    return <LawSearchPage onBack={() => setPage('landing')} onDebug={() => setPage('rag-debug')} />;
  }

  if (page === 'rag-debug') {
    return <RagDebugPage onBack={() => setPage('law-search')} />;
  }

  if (page === 'admin') {
    return <AdminPage onBack={() => setPage('landing')} />;
  }

  return (
    <LandingPage
      admin={authStatus}
      onEnter={() => setPage('law-search')}
      onAdmin={() => setPage('admin')}
      onLogout={handleLogout}
    />
  );
}
