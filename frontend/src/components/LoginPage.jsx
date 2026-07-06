import { useState } from 'react';
import { LockKeyhole, LogIn } from 'lucide-react';
import { loginAdmin } from '../api/lawApi';
import { LandingConstellation } from './LandingPage';

export function LoginPage({ onAuthenticated }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setSubmitting(true);
    setMessage('');
    try {
      const result = await loginAdmin(username, password);
      onAuthenticated(result);
    } catch (error) {
      setMessage(error.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <LandingConstellation interactive={false} showGrid={false} showTexture={false} />
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-mark" aria-hidden="true">
          <LockKeyhole size={22} strokeWidth={1.6} />
        </div>
        <p className="login-kicker">private workspace</p>
        <h1 id="login-title">Pandora Login</h1>
        <form className="login-form" onSubmit={handleSubmit}>
          <label>
            <span>아이디</span>
            <input
              type="text"
              value={username}
              autoComplete="username"
              onChange={(event) => setUsername(event.target.value)}
              disabled={submitting}
            />
          </label>
          <label>
            <span>비밀번호</span>
            <input
              type="password"
              value={password}
              autoComplete="current-password"
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
            />
          </label>
          {message ? <p className="login-error" role="alert">{message}</p> : null}
          <button className="login-button" type="submit" disabled={submitting}>
            <LogIn size={16} strokeWidth={1.7} aria-hidden="true" />
            <span>{submitting ? '확인 중' : '로그인'}</span>
          </button>
        </form>
      </section>
    </main>
  );
}
