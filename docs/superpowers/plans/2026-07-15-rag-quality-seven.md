# Pandora RAG 품질 7점대 실행 계획

> 현재 dirty main checkout에서 기존 변경을 그대로 이어간다. 새 worktree나 reset은 사용하지 않는다. 모든 런타임 조작은 8080에 한정한다.

## 1. 기준선과 런타임 일치

- `git status`, 현재 diff, 8080/18080/Qdrant PID와 app/batch JAR hash를 기록한다.
- `./mvnw.cmd test`로 현재 소스 기준선을 확인한다.
- `./mvnw.cmd -Papp-dev-staged-package clean package`로 staging fat JAR을 만든다.
- manifest, Boot entries, 크기, SHA를 검증한다.
- 8080 서비스만 중지하고 port closed를 확인한 후 staging JAR을 app JAR로 승격하고 8080만 시작한다.
- runtime-info의 artifact SHA/size/instance와 Qdrant readiness를 검증하고 18080 PID 및 batch JAR hash가 불변인지 확인한다.
- 현재 소스로 어려운 12건을 평가해 재현 기준선을 저장한다.

## 2. 동적 index revision

- `IndexRevision` canonicalization과 fail-closed 조건을 먼저 단위 테스트한다.
- Qdrant exact count/config snapshot과 DB current-indexed fingerprint를 읽는 최소 API를 구현한다.
- 같은 snapshot은 같은 revision, 같은 count의 content 교체는 다른 revision, optimizer 통계 변화는 같은 revision임을 검증한다.
- mismatch/red/timeout은 null revision이 되어 full provenance가 거부하는지 확인한다.
- 집중 테스트 후 전체 Maven 테스트를 실행하고 diff를 자체 리뷰한다.

## 3. retrieval recall@K 계측

- TSV case parser의 escaping, optional oracle, no-ground 처리를 Node 테스트로 고정한다.
- 단계별 hit@K/recall, 생존율, 최초 탈락 단계 계산을 fixture 테스트로 먼저 작성한다.
- `scripts/rag-retrieval-eval.js`와 공용 모듈을 구현하고 어려운 12건 기준선을 생성한다.
- Node 집중 테스트와 기존 provenance 테스트, 전체 Maven 테스트를 실행한다.

## 4. intent filter와 heading 검색

- 최신 런타임 debug trace와 retrieval report로 실제 최초 탈락 단계를 확인한다.
- heading-only 후보가 검색되는 실패 테스트를 mapper/서비스 경계에 추가한다.
- 공식 문서 기관 접두어·조사·문서 유형 variant가 intent filter를 통과하는 실패 테스트를 추가한다.
- heading column 직접 검색과 일반화된 title variant만 최소 수정한다.
- 해당 집중 테스트, 어려운 12건의 관련 case, 전체 Maven 테스트를 순서대로 실행하고 diff를 자체 리뷰한다.

## 5. Claim 실패의 OK/캐시 처리

- verifier 거절이 normal/streaming 경로에서 OK와 cache write로 이어지는 실패 테스트를 먼저 작성한다.
- 거절을 기존 응답 계약과 호환되는 명시적 non-OK로 변환하고 캐시를 건너뛰며 실패 stage/type을 기록한다.
- normal/streaming 집중 테스트와 전체 Maven 테스트를 실행하고 diff를 자체 리뷰한다.

## 6. 어려운 12건 재평가

- 새 staging JAR을 같은 8080 전용 절차로 배포한다.
- runtime/index provenance를 확인하고 어려운 12건 answer gate와 retrieval gate를 실행한다.
- 남은 실패를 retrieval, intent, judge, claim, oracle/data 문제로 분류한다.
- 재현되는 일반 실패 클래스만 같은 TDD 순서로 한 번 더 수정한다.

## 7. 전체 1,004건과 최종 리뷰

- 시작 provenance가 완전하고 Qdrant update queue가 idle인지 확인한다.
- 전체 retrieval 보고서와 `node ./scripts/rag-eval-gate.js`를 실행한다.
- 종료 provenance가 시작과 동일한지 검증한다.
- `git diff --check`, 관련 Node 테스트, `./mvnw.cmd test`를 최종 실행한다.
- 기존 변경을 포함한 diff를 correctness, fail-closed, 캐시, concurrency, query scope 관점으로 자체 리뷰한다.
- 통과율, answer verification, recall@K, fail-closed, 평가셋 품질을 근거로 10점 만점 재평가와 남은 위험을 보고한다.
