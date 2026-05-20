import { ArrowRight } from 'lucide-react';

export function LandingPage({ onEnter }) {
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
