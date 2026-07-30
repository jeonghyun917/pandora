# Pandora 원자적 Claim–Evidence 관계 판정 설계

## 목표

2026-07-20 전체 평가에서 `CONTRADICTED` 또는 `CONFLICTED`로 분류된
38건을 실제 claim과 선택 근거에 따라 다시 판정한다.

- 모순 오검출 33건은 `SUPPORTED` 또는 `INSUFFICIENT`로 교정한다.
- 실제 직접 모순 1건과 범위·출처·절차를 합친 과장 4건은 계속
  fail-closed로 거절한다.
- 특정 평가 문항 ID나 문서명에 대한 운영 코드 분기는 만들지 않는다.
- 기존 전체 답변 fail-closed 정책은 변경하지 않는다.

## 확인된 원인

정식 결과물
`logs/rag-eval-gate-full-post-hardening-post-retry-fix-20260720.json`의
실패를 재분석한 결과는 다음과 같다.

- 대상 사례: 38건
- 모순/충돌 링크: 74개
- `CONTRADICTED`: 54개
- `CONFLICTED`: 20개
- coverage 0.34 미만 링크: 34개
- 낮은 coverage 모순 후보가 포함된 사례: 26건
- 직접 지원이 있는데 긴 근거 조각의 반대 표현이 섞인 오검출: 22건
- 다른 대상·행위의 근거를 모순으로 선택한 오검출: 10건
- 확인 필요 문구를 모순 claim으로 처리한 오검출: 1건
- 실제 직접 모순: 1건
- 실제 범위·출처·절차 과장: 4건

주요 원인은 다음 세 가지가 함께 작동하는 것이다.

1. `ClaimEvidenceMatcher`가 긴 OCR 근거 조각 전체에 하나의
   대상·의무·허용 polarity를 부여한다.
2. 동일 대상·행위·조건인지 확인하기 전에 반대 polarity를
   `CONTRADICTED`로 확정한다.
3. 지원 후보에는 최소 coverage를 적용하지만 모순 후보에는 적용하지
   않으며, 지원 후보와 임의의 모순 후보가 하나씩만 있어도
   `CONFLICTED`로 확정한다.

따라서 일반 규칙과 예외, 허용과 금지, 의무와 생략 조건이 같은 OCR
조각에 포함되면 서로 다른 proposition의 의미가 오염된다.

## 검토한 접근

### 접근 1: 모순 후보의 임계값만 강화

모순 후보에도 지원 후보와 같은 coverage 기준을 적용한다. 변경은 작지만
긴 근거 조각에 직접 지원 문장과 예외가 함께 있는 경우를 복구하지 못한다.
많은 오검출을 `INSUFFICIENT`로 바꿀 수는 있어도 직접 지원을 살리지
못하므로 단독 해법으로 사용하지 않는다.

### 접근 2: 원자적 proposition 판정과 보수적 모순 게이트

근거를 구조적 경계와 조건·예외 경계로 원자화하고, 동일 proposition
정렬을 통과한 후보에만 polarity 비교를 적용한다. 직접 지원을 보존하면서
다른 행위의 반대 표현은 모순이 아닌 근거 부족으로 처리할 수 있다.
이 설계를 채택한다.

### 접근 3: 외부 LLM/NLI 판정 추가

자유로운 문장 의미 비교에는 유리하지만 비용·지연·비결정성이 증가하고,
최종 안전 게이트의 재현성이 낮아진다. 이번 범위에서는 사용하지 않는다.

## 아키텍처

### 1. 근거 원자화

새 package-private 순수 컴포넌트
`ClaimEvidenceAtomizer`가 근거 텍스트를 `List<String>` 원자로 변환한다.
이 컴포넌트는 법적 의미를 판정하지 않고 구조적 경계만 담당한다.

원자 경계는 다음과 같다.

- 마침표·물음표·느낌표·개행·세미콜론
- PDF/OCR 목록 표지: 원형 번호, `•`, `‣`, `□`, `○`, `※`
- 표와 페이지가 합쳐진 명시적 행·페이지 경계
- `다만`, `예외적으로`처럼 앞 규칙과 뒤 예외를 구분하는 접속 경계
- 독립 서술어를 연결하는 `이며`, `이고`, `되며`, `하되` 경계

조건구와 그 결론은 분리하지 않는다. `~인 경우`, `~에 한하여`,
`~이면`과 뒤의 대상·의무·허용 서술은 하나의 원자로 유지한다.
너무 짧은 조각이나 표 머리글은 버리지 않고 인접 원자의 anchor context로
보존하여 대상·조건 정보가 사라지지 않게 한다.

`ClaimEvidenceMatcher.addEvidenceFragments`는 child text, snippet,
parent context 각각을 atomizer에 전달하고 원자별 token, 숫자, 의미 정보를
인덱싱한다. 서로 다른 ground의 원자를 합쳐 하나의 support로 만들지 않는다.

### 2. 동일 proposition 정렬

후보는 polarity 비교 전에 다음 정렬 조건을 통과해야 한다.

- 최소 token overlap과 coverage
- 숫자 값·순서·숫자가 수식하는 대상
- 관계 참여자와 명시적 relation anchor
- 조건·예외 anchor와 적용 범위
- 허용·금지의 대상이 되는 행위
- 계약방식·제재·기한 같은 의미 category
- 명시된 주체·객체·수신자 방향

claim이 요구한 anchor가 근거 원자에 없으면 `NOT_ENTAILED`로 처리한다.
서로 반대 polarity가 있더라도 대상·행위·조건이 다르면
`CONTRADICTED`가 아니라 `NOT_ENTAILED`다.

현재의 relation, condition, numeric, permission-action anchor를 재사용한다.
관찰된 실패를 설명하지 않는 일반 형태소 분석기나 별도 NLP 의존성은
추가하지 않는다. 주체·객체 방향은 명시적 조사와 기존 token 순서를
이용하는 최소 signature로 한정한다.

### 3. polarity와 conflict 판정

동일 proposition 정렬을 통과한 뒤에만 다음 polarity를 비교한다.

- 대상 포함 ↔ 대상 제외
- 의무 ↔ 의무 없음·생략 가능
- 허용 ↔ 금지·불가능

지원 후보와 모순 후보 모두 동일한 최소 coverage 기준을 통과해야 한다.
단, 숫자 claim에 적용되는 기존 별도 coverage 기준은 유지한다.

판정 우선순위는 다음과 같다.

1. 동일 proposition의 직접 지원 원자만 존재하면 `SUPPORTED`
2. 동일 proposition의 직접 반대 원자만 존재하면 `CONTRADICTED`
3. 동일 proposition에 직접 지원과 직접 반대 원자가 모두 존재하면
   `CONFLICTED`
4. 나머지는 `INSUFFICIENT`

낮은 coverage의 반대 표현이나 다른 행위의 반대 표현은
`CONFLICTED`를 만들 수 없다. 충돌 링크에는 실제 반대 원자를 기록한다.

### 4. ClaimVerifier 안전 계약

`ClaimVerifier`의 전체 답변 정책은 유지한다.

- 하나라도 실제 `CONTRADICTED` 또는 `CONFLICTED` claim이 있으면
  표준 근거 부족 응답으로 fail closed한다.
- 직접 지원 claim은 유지한다.
- 모순은 아니지만 지원되지 않은 claim은 제거한다.
- 지원되는 강한 claim이 하나도 남지 않으면 표준 근거 부족 응답으로
  fail closed한다.

이번 단계에서는 생성 prompt, 검색·judge, 캐시·streaming 계약을 바꾸지
않는다. 실제 과장 5건을 더 잘 답하도록 만드는 작업은 관계 판정 교정이
검증된 뒤 별도 단계로 수행한다.

## 데이터 흐름

1. 선택된 ground의 child/snippet/parent text를 각각 원자화한다.
2. 원자별 token·숫자·조건·관계·행위·polarity signature를 만든다.
3. answer의 강한 claim마다 후보 원자를 검색한다.
4. proposition 정렬 조건으로 다른 대상·행위·조건 후보를 제거한다.
5. 남은 후보에만 polarity를 비교한다.
6. 동일 proposition의 지원·모순 후보 집합으로 최종 status를 정한다.
7. `ClaimVerifier`가 기존 fail-closed 계약에 따라 답변을 유지·정리·거절한다.

## 실패 처리와 관측성

- 원자화 결과가 없거나 정렬 가능한 원자가 없으면
  `INSUFFICIENT`로 처리한다.
- 구조가 불명확한 OCR 조각을 추측하여 `SUPPORTED`로 올리지 않는다.
- 모순 링크에는 ground 번호, 선택 원자, overlap, coverage를 계속 기록한다.
- 진단 테스트와 평가 결과에서 원래 긴 fragment 대신 실제 판정에 사용된
  원자가 표시되어야 한다.
- 원자화 예외는 발생시키지 않는 순수 문자열 처리로 구현한다. null·blank
  입력은 빈 원자 목록을 반환한다.

## TDD 검증 설계

현재 구현에서 먼저 실패해야 하는 최소 진단 fixture는 다음과 같다.

1. 일반 대상 규칙과 명시적 제외 예외가 한 조각에 함께 있는 경우
2. 공개장소 CCTV 원칙적 금지와 명시적 설치 허용 예외가 함께 있는 경우
3. 직접 제출 의무와 무관한 생략 목록이 긴 OCR 조각에 함께 있는 경우
4. 분리보관 의무와 불필요 시 파기 예외가 함께 있는 경우
5. 두 token만 겹치는 다른 행위의 반대 polarity 근거
6. 확인 필요 문구가 다른 의무와 모순으로 처리되는 경우
7. 동일 proposition의 실제 직접 반대 근거
8. 실제 피해와 장래 위험의 보호 절차를 합친 compound claim
9. 동일 의미를 마침표·쉼표·`다만`·개행·bullet·표 행으로 표현한 경계 행렬

RED 단계에서 현재 오검출 status와 evidence link를 확인한다. GREEN
단계에서는 1~6과 경계 행렬이 `SUPPORTED` 또는 `INSUFFICIENT`가 되고,
7은 `CONTRADICTED`, 8은 지원되지 않거나 모순으로 거절되어야 한다.

기존 true-conflict, negated permission, negated requirement, numeric,
condition, relation-anchor 테스트는 모두 회귀 방지 control로 유지한다.

## 평가 순서

각 구현 단계는 다음 순서를 지킨다.

1. 원인과 해당 fixture를 기록한다.
2. 실패하는 원자 테스트를 추가하고 RED를 확인한다.
3. 가장 작은 일반화된 구현만 추가한다.
4. 해당 matcher/verifier 집중 테스트를 실행한다.
5. diff를 자체 리뷰하고 독립 코드리뷰를 받는다.
6. 전체 Maven 테스트를 실행한다.
7. 38건의 명시적 case ID를 대상으로 평가를 두 번 실행하여 변동성을 본다.
8. 실제 문제 5건이 계속 fail-closed인지 별도로 확인한다.
9. runtime artifact/config/index가 안정적일 때만 전체 1,004건을 재평가한다.

런타임 검증이 필요하면 공식 스크립트로 8080만 재기동한다. Qdrant 6333은
상태 확인과 검색에 사용할 수 있다. 18080 batch-runner는 중지·재기동·승격
대상에서 제외한다.

## 수용 기준

- 9개 진단 fixture와 기존 회귀 control이 모두 통과한다.
- 관찰된 오검출 33건의 고정 claim/evidence replay가
  `CONTRADICTED`/`CONFLICTED`로 잘못 분류되지 않는다.
- 직접 지원이 확인된 fixture는 `SUPPORTED`, 직접 지원이 없는 fixture는
  `INSUFFICIENT`로 구분된다.
- 실제 직접 모순 1건은 `CONTRADICTED`를 유지한다.
- 실제 범위·출처·절차 과장 4건은 `SUPPORTED`로 승격되지 않고 최종 답변이
  fail-closed를 유지한다.
- 새로운 forbidden-answer, no-ground false answer, numeric 또는 deadline
  안전 회귀가 없어야 한다.
- 전체 평가에서는 curated와 answer-verification 결과를 generated 결과와
  분리해 보고하며, 94.82% 전체 통과율만으로 품질 향상을 주장하지 않는다.

## 비목표

- 38건을 무조건 통과시키는 문항별 예외
- 전체 한국어 문장 분석기 도입
- 외부 LLM/NLI를 최종 관계 판정기로 사용
- 검색·judge·생성 prompt의 동시 변경
- 기존 fail-closed 기준 완화
- 18080 batch-runner 조작
