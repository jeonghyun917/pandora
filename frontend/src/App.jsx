import { useEffect, useState } from 'react';
import { fetchAuthStatus, logoutAdmin } from './api/lawApi';
import { LawSearchPage } from './components/LawSearchPage';
import { RagDebugPage } from './components/RagDebugPage';
import { AdminPage } from './components/AdminPage';
import { LoginPage } from './components/LoginPage';

export function App() {
  const [page, setPage] = useState('law-search');
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
    setPage('law-search');
  }

  function handleAuthenticated(result) {
    setAuthStatus(result);
    setPage('law-search');
  }

  if (authStatus === null) {
    return (
      <main className="auth-page">
        <section className="auth-loading">Checking session</section>
      </main>
    );
  }

  if (!authStatus.authenticated) {
    return <LoginPage onAuthenticated={handleAuthenticated} />;
  }

  if (page === 'law-search') {
    return (
      <LawSearchPage
        onAdmin={() => setPage('admin')}
        onDebug={() => setPage('rag-debug')}
        onLogout={handleLogout}
      />
    );
  }

  if (page === 'rag-debug') {
    return <RagDebugPage onBack={() => setPage('law-search')} />;
  }

  if (page === 'admin') {
    return <AdminPage onBack={() => setPage('law-search')} />;
  }

  return null;
}
