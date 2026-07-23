# RAG 직접 근거 커버리지 계측 및 원자 우선순위 설계

## 목표

85개 명시적 answer-oracle 사례에서 현재 관측된 proposition 누락 80건과
조건 누락 74건을 검증 완화로 숨기지 않는다. 각 필수 그룹이 검색 후보,
필터·판정 결과, 최종 근거, 검증된 답변 중 어느 단계까지 존재하는지
측정하고, 이미 `SUPPORTED`이며 질문과 정렬된 근거 원자 사이에서만
직접 근거를 우선한다.

## 비목표와 안전 경계

- oracle 표현을 운영 검색어, rerank 점수 또는 답변 생성 프롬프트에 넣지 않는다.
- `SUPPORTED`가 아니거나 질문 proposition과 정렬되지 않은 원자를 살리지 않는다.
- contradiction/conflict 처리와 전체 답변 fail-closed 정책을 완화하지 않는다.
- 18080 batch-runner를 중지·재기동·교체하지 않는다.
- 사례 ID별 하드코딩이나 질문별 예외를 추가하지 않는다.

## 검토한 접근

### 1. 최종 답변 JSON만 분석

구현은 가장 작지만 최종 선택 이전 단계가 보이지 않는다. 검색에 근거가
있었는지와 답변 생성 중 사라졌는지를 구분할 수 없어 채택하지 않는다.

### 2. 기존 debug snippet으로 단계별 분석

검색 단계는 볼 수 있지만 snippet이 원문의 일부만 포함하므로 실제 근거가
chunk 뒤쪽에 있는 경우 거짓 누락으로 분류한다. 진단 신뢰도가 부족해
채택하지 않는다.

### 3. 계측 전용 전체 matched-child 본문과 단계별 커버리지 행렬

채택안이다. 검색 평가 요청에 명시적인 계측 플래그를 보내고, 보호된 debug
응답이 그 요청에 한해서만 각 chunk의 전체 matched-child 본문을 포함하게
한다. 일반 UI debug 응답과 사용자 답변에는 전체 본문을 추가하지 않는다.
Node 평가기는 oracle 그룹을 측정에만 사용해 각 단계의 존재 여부와 최초
소실 단계를 기록한다.

## 측정 모델

측정 대상 단계는 다음과 같다.

1. `candidateSources`: vector·lexical 후보의 중복 제거 합집합
2. `merged`
3. `reranked`
4. `intentFiltered`
5. `judgeCandidates`
6. `judged`
7. `selected`
8. `supportedEvidence`: 최종 평가 결과의 `SUPPORTED` evidence sentence
9. `verifiedAnswer`

검색 단계 1~7은 새 retrieval 결과에서 측정한다. 8~9는 같은 case ID의
완전한 answer-eval JSON을 명시적으로 입력받아 결합한다. 서로 다른
runtime instance, artifact SHA-256, index revision 또는 dataset hash의
결과는 결합을 거부한다.

각 proposition/condition 그룹은 AND 그룹이고 그룹 내부 표현은 OR
alias다. 하나의 검색 item 안에서 alias 하나가 명시적으로 일치하면 그
그룹이 존재한 것으로 본다. 서로 다른 item의 토큰을 이어 붙여 하나의
명제를 만들지 않는다. `supportedEvidence`도 한 evidence sentence 안에서
일치해야 한다.

보고서는 다음을 포함한다.

- 단계별 proposition/condition 그룹 커버리지
- 사례별 최초 소실 단계
- 검색 후보에도 없었던 그룹
- selected까지 존재했지만 supportedEvidence에서 사라진 그룹
- supportedEvidence에는 있었지만 verifiedAnswer에서 사라진 그룹
- 계측 불완전·provenance 불일치·요청 오류

## 명시적 표현 매칭

Java answer oracle과 의미가 어긋난 단순 substring 계측을 피한다. Node에
작은 explicit-oracle matcher를 두어 다음을 적용한다.

- NFKC, 소문자, 구두점·공백 정규화
- alias의 핵심 어휘가 한 item 또는 한 sentence 안에 모두 존재해야 함
- 긍정 alias를 부정 문장이 충족시키거나 부정 alias를 긍정 문장이
  충족시키지 않도록 국소 polarity를 확인

Java와 Node의 대표 긍정·부정·조건 사례를 동일 fixture로 검증한다. 이
matcher는 평가 계측 전용이며 운영 답변 판정에는 사용하지 않는다.

## 직접 근거 원자 우선순위

계측 결과에 따라 수정 위치를 결정한다.

- 필수 그룹이 `candidateSources`에 없으면 원자 순위는 수정하지 않는다.
  별도의 retrieval recall 작업 대상으로 보고한다.
- `intentFiltered` 또는 `judged`에서 주로 사라지면 해당 필터/판정의 가장
  작은 일반 규칙을 별도 TDD로 수정한다.
- `selected` 또는 `supportedEvidence`까지 존재하지만 6개 repair 원자에서
  빠질 때만 repair 원자 우선순위를 수정한다.

repair 우선순위는 안전 gate를 통과한 후보에만 적용한다. 모든 후보를 기존과
같이 개별 verify한 뒤 다음 운영 신호로 안정 정렬한다.

1. 질문의 subject·relation·condition 핵심어 직접 커버리지
2. 질문 의도와 일치하는 절차·의무·예외·기한 관계 표현
3. 짧고 독립적인 원자에 대한 우대와 구조·메타데이터 잡음 감점
4. 동점이면 기존 ground 순서와 source 순서

평가 oracle 표현과 case ID는 점수에 관여하지 않는다. 최대 6개, 원자별
360자, 전체 1,500자 제한도 유지한다.

## 오류 처리

- 계측 플래그가 없으면 `matchedChildText`는 응답에서 비어 있어야 한다.
- 계측 플래그가 있는데 필드가 없거나 빈 본문만 반복되면 평가를 불완전으로
  표시하고 성공으로 기록하지 않는다.
- answer-eval 결합 파일이 없으면 검색 1~7단계만 보고하되 8~9단계를
  `not_measured`로 표시한다.
- runtime 또는 index가 평가 중 바뀌면 기존과 같이 실행을 실패시킨다.
- provenance가 다른 answer-eval 파일은 결합하지 않고 오류를 낸다.

## 테스트와 성공 기준

### 집중 테스트

- debug 요청의 계측 플래그 기본값은 false다.
- 플래그가 true일 때만 전체 matched-child 본문이 노출된다.
- alias AND/OR, 단일-item 경계, polarity, 최초 소실 단계가 정확하다.
- provenance 불일치 결합은 실패한다.
- 우선순위가 필요하다고 측정된 경우, 지원·정렬된 직접 원자는 6개 경계
  안으로 이동하고 unsupported/misaligned 원자는 절대 선택되지 않는다.

### 전체 검증

- `.\mvnw.cmd test`
- Node retrieval/eval 테스트
- 최신 8080에 대한 85건 단계별 retrieval 계측
- 변경이 운영 answer 경로에 들어간 경우 동일 85건 answer gate 재평가
- 비거절 답변의 unsupported/contradicted/forbidden 0건 유지
- 평가 오류 0건, runtime·index provenance 안정

전체 1,004건 평가는 85건에서 안전성과 개선이 확인된 뒤에만 실행한다.
