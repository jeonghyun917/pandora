# 키워드 검색 시간 초과 수정 및 답변 평가 — 2026-09-29

## 결과

- 기반 커밋: `8f54c38c188c2103b30342cd10aba08e00465bb0`.
- 일반화된 변경: `findSemanticChunksByText`의 검색어 테이블과 동적 LIKE 조인을 검색어별 상수 prefix 범위 조회의 UNION ALL로 변경했다.
- 문서 유형/활성/최신 청크/품질 필터, 일치 검색어 수, exact-match 가산점, 결과 제한과 SQL 3초 제한은 유지했다. 답변 검증기와 평가 oracle은 변경하지 않았다.
- 전체 백엔드 테스트: **1392 실행, 실패 0, 오류 0, skipped 18**, 52.163초. 관련 XML mapper 테스트는 변경 전 의도된 실패 2개, 변경 후 8/8 통과.
- live 평가: 관계 질문 1/1 통과, 관련 질문 2/3 통과. 합계 **3/4**이며 전체 릴리즈 승인 결과가 아니다.

## 확인된 원인과 읽기 전용 측정

변경 전 관계 질문은 `사전협의/대상사업/대상기관` SQL에서 MariaDB 1969/3초 제한 오류가 발생했고, 이어 전체 lexical future가 시간 초과로 폐기되었다. 정답 근거 87923이 선택되지 않았고, 지원되지 않는 범위 주장을 검증기가 정상적으로 거절했다.

같은 세 검색어를 대상으로 EXPLAIN 확인:

- 기존 동적 LIKE 조인: `type=index`, 예상 rows **10,125,397**, join buffer BNL.
- 검색어별 상수 prefix 조회: `type=range`, PRIMARY 사용, 예상 rows **814 / 208 / 553**.
- 변경 구조를 문서/청크 필터와 점수 집계까지 포함해 읽기 전용 실행: **313ms**(CLI 기동 포함 단일 관측). 청크 87923이 상위 3개 안에 포함됐다.
- 이는 단일 로컬 측정이며 전체 요청 p95 성능 개선률을 의미하지 않는다.
- 호출자는 indexedRagKeywords에서 한글/영숫자 토큰으로 정규화한다. prefix 일치는 exact 일치를 포함하며 exact 점수 가산은 별도로 유지된다.

## 실제 답변 평가

| 사례 | 결과 | 설명 |
|---|---|---|
| project-review-pre-consultation-relation | PASS | 수정 전 FAIL. 수정 후 87923이 최종 근거에 포함되고 answerVerified=true |
| pre-consultation-exception | PASS | answerVerified=true |
| security-review-target | FAIL | 검색 기대 조건 충족. 지원되지 않는 주장/모순은 없으나 보정 답변에서 질문의 대상 문맥이 사라져 필수 답변 명제 누락 |
| security-review-exception | PASS | answerVerified=true. 근거 부족을 설명하는 문장은 unsupported 목록에 있으므로 '모든 문장 근거 일치'로 과장하지 않음 |

원본 증거:

- [수정 전 관계 평가](rag-eval-relation-8f54c38c-20260929.json)
- [수정 후 관계 평가](rag-eval-relation-prefix-range-20260929.json)
- [관련 3개 평가](rag-eval-related-prefix-range-20260929.json)

변경 후 평가에서 lexical timeout/batch failure 로그는 없었다. 각 gate의 오류 재시도는 0회였다.

## 배포 및 경계

- app-dev 8080만 공식 배포 스크립트로 반영.
- JAR: `436bbd2a8212d1281b26789c8df586124cc3138478ed120b049c7e0636fb53b2`
- 런타임: `02daaa73-ca7e-4b26-a84c-2c290b96ba84`
- config: `e7b08ced10e7fd56f1dbfda7822dccb019aec056cf31ea16fca24604cbd1576a`
- index: `726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285`
- lexical: `da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa`
- Qdrant ready=true, search failure count=0. 18080/output는 변경하지 않음. 검색 authority 플래그는 변경하지 않음.

## 다음 작업

1. security-review-target의 `REWRITE_ACCEPTED`, selectedAtomCount=3 경로에서 질문 주제/대상 문맥이 사라지는 지점을 회귀 테스트로 재현한다. 명제/주체를 근거 없이 덧붙이거나 oracle을 느슨하게 하지 않는다.
2. 해당 문제를 일반화된 최소 변경으로 해결한 뒤 이 사례와 관련 사례를 재검증한다.
3. 그 다음 Difficult-12 → holdout → 전체 1003개 릴리즈 게이트. 이번 턴에는 전체 게이트를 실행하지 않았다.
