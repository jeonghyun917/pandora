# Pandora RAG 품질 7점대 복구 설계

## 목표

현재 변경을 보존하면서 handoff의 중단점부터 검색·근거선정·검증 실패를 계측하고 일반화된 수정으로 품질을 7점대로 끌어올린다. 최우선 안전 기준은 근거 없는 답변을 생성하지 않는 것이다.

## 확인된 원인

1. 8080이 사용하는 `target` JAR이 실행 중 일반 package 과정에서 thin JAR로 덮였다. 실행 프로세스는 반복적인 Boot loader EOF 오류를 내므로 현재 평가는 최신 소스의 신뢰할 수 있는 기준선이 아니다.
2. Qdrant 두 컬렉션은 현재 green이고 exact point count와 DB의 현재 indexed count가 일치하지만, 전체 평가 provenance가 요구하는 동적 `indexRevision`은 소스에서 명시적으로 `null`이다.
3. 기존 평가는 최종 정답 여부는 재지만 검색 단계별 recall@K를 독립적으로 재지 않아, 검색 누락과 intent/judge 단계 탈락을 분리하기 어렵다.
4. heading 보조 검색은 문서 메타데이터로 문서를 찾은 뒤 chunk를 반환하고, 정작 chunk heading을 직접 찾지 않는 경로가 있다.
5. 공식 문서 조회 intent는 제목 일치 후보가 없으면 후보 전체를 비울 수 있다. 최신 소스의 제목 정규화 개선 후에도 재현되는지 먼저 확인해야 한다.
6. Claim 검증 거절 뒤에도 정상 `OK`·캐시·stream 성공으로 취급되는 경로가 있어 fail-closed 계약과 충돌한다.

## 설계 원칙

- 검색은 넓게, 최종 근거는 좁게 선택한다.
- 각 수정은 실패 테스트로 원인을 고정한 다음 최소한의 일반 규칙만 바꾼다.
- 문항 ID나 특정 문서명에 대한 one-off 분기는 만들지 않는다.
- 8080만 중지·기동한다. 18080과 batch JAR은 변경하지 않는다.
- 평가 시작과 끝의 runtime artifact, config, instance, Qdrant readiness, failure count, index revision이 같아야 결과를 채택한다.

## 런타임 및 index revision

8080은 공식 `app-dev-staged-package` 산출물만 사용한다. staging fat JAR의 Boot manifest와 `BOOT-INF/classes`, `BOOT-INF/lib`, 크기 및 SHA-256을 검증하고 8080이 완전히 중지된 상태에서만 `target`으로 승격한다.

`indexRevision`은 두 컬렉션의 안정된 검색 스냅샷을 canonical 문자열로 만든 SHA-256이다. 컬렉션별 입력은 collection/model, DB current-indexed count, DB의 현재 content fingerprint/watermark, Qdrant exact count, vector size, distance이다. Qdrant가 green이 아니거나 update queue가 idle이 아니거나 DB와 exact count가 다르면 revision을 발급하지 않는다. optimizer가 비동기로 바꾸는 segment 수와 `indexed_vectors_count`는 hash에서 제외한다.

현재 스키마에서 비용과 정확성을 함께 만족하는 fingerprint를 우선 사용한다. 같은 개수의 내용 교체도 탐지해야 하며, 불가능하면 monotonic revision ledger를 도입한다. 단순 count만으로 revision을 발급하지 않는다.

## retrieval recall@K

기존 debug 응답의 `vectorHits`, `lexicalHits`, `merged`, `reranked`, `intentFiltered`, `judgeCandidates`, `selected`를 이용한다. TSV parser는 공용 모듈로 분리하고 다음을 산출한다.

- 단계별 document hit@K와 section/parent hit@K
- 단계 간 생존율과 최초 탈락 단계
- 검색 정답이 정의되지 않은 no-ground 문항은 recall 분모에서 제외하고 false-ground만 별도로 센다.

문서 gold는 expected title/document terms를, 구조 gold는 expected section/parent terms를 사용한다. required answer terms만으로 검색 성공을 판정하지 않는다.

## 검색과 검증 수정

- heading 검색은 최신 활성 문서 버전의 PASS/REVIEW 검색 가능 chunk에서 `chunk_title`과 `parent_section_title`을 직접 매칭하고, 문서 메타데이터는 보조 신호로만 사용한다.
- 공식 문서 intent는 제목 parser가 만드는 정규화된 full/core title과 기관 접두어 제거 variant로 후보를 평가한다. 직접 제목 후보가 정말 없을 때만 fail closed하며, 일반 질의의 후보까지 비우지 않는다.
- Claim 검증 거절은 명시적인 non-OK 결과가 되고 답변 캐시에 저장하지 않는다. streaming도 `done.ok=false`로 끝나며 실패 stage/type을 기록한다.

## 검증 게이트

각 단계는 원인 분석, RED 테스트, 최소 수정, GREEN 집중 테스트, diff 자체 리뷰, 전체 Maven 테스트 순으로 닫는다. 마지막에는 어려운 12건을 먼저 재평가하고, provenance가 안정적일 때만 전체 1,004건을 실행한다. 최종 점수는 실제 게이트 결과, 검색 recall, fail-closed 동작, 데이터셋 신뢰도를 분리해 다시 산정한다.
