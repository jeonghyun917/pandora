import { useState } from 'react';
import { LockKeyhole, LogIn } from 'lucide-react';
import { loginAdmin } from '../api/lawApi';
import { LandingConstellation } from './LandingPage';

const COPY = {
  username: '\uC544\uC774\uB514',
  password: '\uBE44\uBC00\uBC88\uD638',
  checking: '\uD655\uC778 \uC911',
  login: '\uB85C\uADF8\uC778',
};

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
      <section className="auth-hero" aria-labelledby="login-title">
        <p className="auth-kicker">law open data workspace</p>
        <h1 id="login-title">pandora</h1>
        <form className="login-panel login-form" onSubmit={handleSubmit}>
          <div className="login-panel-heading">
            <div className="login-mark" aria-hidden="true">
              <LockKeyhole size={21} strokeWidth={1.6} />
            </div>
            <p className="login-kicker">private access</p>
          </div>
          <label>
            <span>{COPY.username}</span>
            <input
              type="text"
              value={username}
              autoComplete="username"
              onChange={(event) => setUsername(event.target.value)}
              disabled={submitting}
            />
          </label>
          <label>
            <span>{COPY.password}</span>
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
            <span>{submitting ? COPY.checking : COPY.login}</span>
          </button>
        </form>
      </section>
    </main>
  );
}
