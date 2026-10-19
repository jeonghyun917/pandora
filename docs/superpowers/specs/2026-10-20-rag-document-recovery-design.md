# 공식문서 단위 비활성 후보·원자적 승격·롤백

## 목적과 범위

문서 1765의 기존 활성 청크 157908~157911, 기존 임베딩/벡터와 키워드를 보존하면서, 동일 원본에서 생성한 17개 청크를 별도 후보로 검증하고 승격·롤백한다. 문서 ID를 하드코딩한 제품 분기는 만들지 않는다. 법령/행정규칙 재구축이나 대량 재수집, 기존 의미 verifier의 완화는 범위 밖이다. 예약 작업은 PAUSED 유지.

이는 DB, Qdrant, 메모리/로컬 BM25 색인의 다중 저장소 변경이다. 이들을 하나의 분산 트랜잭션으로 갱신했다고 주장하지 않는다. **후보를 먼저 준비하고, DB의 문서 세대 포인터만 원자적으로 바꾸며, 모든 검색 경로가 그 포인터의 일관된 snapshot을 사용한다**는 계약을 구현한다.

## 현재 확인된 위험

- `RagImportService.importFile(force=true)`는 기존 임베딩 supersede, 청크 deactivate와 키워드 rebuild를 수행한다. indexNow=false도 preview가 아니다.
- `RagChunkSearchIndexService.rebuildDocument`는 문서의 이전 키워드/상태를 삭제한다. 후보용으로 호출하지 않는다.
- 기존 청크는 `use_yn`만 갖고 동일 chunk_version=4 안에 별도 세대가 없다. fileHash와 chunkVersion만으로 세대를 식별할 수 없다.
- Q 검색 결과는 DB active 검사를 거치지만 후보 벡터를 기존 collection에 먼저 넣으면 top-k를 점유해 이전 검색의 재현율을 떨어뜨릴 수 있다. 최종 hydration 필터만으로 비노출을 증명하지 않는다.

## 대안과 선택

1. 기존 행 덮어쓰기/force reimport: 복구 중 이전 검색을 보존하지 못하므로 제외.
2. 동일 collection에 후보 점수를 섞고 DB에서 제외: 잘못된 본문 반환은 줄여도 후보 top-k 오염이 있어 제외.
3. **세대 격리 후보 + DB-authoritative 원자 포인터 + 세대별 검색 snapshot**: 권고안. 기존 인터페이스를 최대한 유지하되 영향 있는 검색 경로를 명시적으로 검증한다.

## 데이터와 상태

- 문서별 복구 generation 레코드: generation UUID, documentId, 원본 SHA, 제품 SHA, 추출/청킹 버전 식별, 이전 활성 세대 snapshot 해시, 후보 manifest 해시, 상태, 오류 단계, 생성/검증/승격 시각.
- 기존 청크를 baseline 세대에 연결하는 migration은 ID·본문·use_yn·embedding·index 상태를 변경하지 않는다. 중복 baseline 생성은 unique 제약으로 차단한다.
- 새 후보는 새 청크 ID 및 generation ID를 가진다. PENDING→VALIDATED→INDEX_READY→ACTIVE→RETIRED. 오류는 FAILED, 동시성 불일치는 STALE. FAILED/STALE은 자동 재승격하지 않는다.
- 문서에는 activeGeneration 및 단조 증가 revision을 둔다. 이전 세대를 지우지 않는다. 세대 상태와 포인터는 하나의 DB 트랜잭션에서 CAS로 변경한다.
- READY는 본문 의미검증과 검색자산 parity를 모두 충족한 상태만 허용한다. 관리자 요청으로 READY를 직접 지정할 수 없다.

## 후보 생성과 검증

preview는 원본 전후 SHA, 문서 메타데이터, 기존 활성 ID/본문/표 proof/색인 식별을 읽고 manifest를 계산한다. create는 문서 잠금 아래 preview snapshot을 재검증한 뒤 비활성 후보만 삽입한다. 동일 요청은 같은 manifest의 후보를 반환하여 중복을 만들지 않는다.

원본 t 노드 문자 보존은 필요조건이며 충분조건이 아니다. sourcePart/tablePath/rowIndex/cell 좌표·span/covered ranges가 원본과 맞아야 하고, 반복 헤더·병합셀·분할행·서로 다른 표·문서 말미 단서를 독립 검증한다. 기관/날짜 치환, 전제·단서 삭제, 다른 표의 셀 전용은 fail-closed 한다. 원문 표 구조가 자연어 의무를 자동 증명하지 않으며 기존 최종 verifier는 변경하지 않는다.

검증 증거는 source SHA, product SHA, 후보 manifest에 묶는다. 바뀐 본문에 이전 통과 증거를 재사용하지 않는다. 테스트/평가 질문을 제품에 넣지 않는다.

## 검색자산 격리와 전환

- 후보 임베딩은 후보 전용 Q collection/namespace에 준비한다. 기존 main collection의 포인트를 수정하거나 삭제하지 않는다. 키워드 자산도 generation별로 생성하고 이전 자산을 삭제하지 않는다.
- 요청 시작 시 활성 generation/revision snapshot을 취득한다. 해당 세대의 벡터와 키워드만 검색한다. vector/keyword/context/parent/title/최종 hydration이 서로 다른 generation을 섞지 않는다.
- baseline 공용 collection을 계속 사용하는 동안 교체 대상 문서의 오래된 포인트는 vector query 단계에서 제외하고 활성 세대 전용 collection 결과를 합친다. 후보/retired 문서가 top-k를 점유하지 않는 테스트가 필수다. 이 라우팅이 구현·검증되기 전 promote endpoint는 항상 거부한다.
- 승격은 외부 네트워크 작업 없이 수행한다. 문서/후보/이전 generation 잠금→source/snapshot/revision/manifest 및 준비된 Q/keyword 증거 확인→CAS activeGeneration 변경→commit. DB 실패는 이전 포인터를 유지한다.
- Q/keyword 자산이 검증 후 외부에서 사라지면 그 세대 검색은 명시적 unavailable로 fail-closed 한다. 과거 세대를 조용히 섞거나 DB만 성공으로 보고하지 않는다. readiness 확인과 운영 관측은 승격 전후 모두 수행한다.
- 기존 요청은 취득한 이전 snapshot으로 완료할 수 있다. 새 요청은 새 포인터를 본다. 오래된 cache는 document revision으로 분리하고 포인터 전환 후 신규 요청에 재사용하지 않는다.

## 롤백과 오류

롤백도 현재 revision/activeGeneration CAS를 요구한다. 보존된 baseline 청크/벡터/keyword manifest와 준비 상태를 재확인한 뒤 포인터를 이전 세대로 원자 변경한다. 후보 포인트를 되돌려 덮거나 기존 임베딩을 다시 생성하지 않는다. 더 최신 세대가 있으면 stale rollback을 거부한다.

프로세스 중단 후 CREATING/INDEXING 상태는 단계별 manifest로 진단한다. 불명확한 네트워크 결과는 조회로 확인하며 blind retry하지 않는다. 정리는 본 작업에서 자동 실행하지 않고, rollback 보존기간 이후 별도 명시 동작으로만 허용한다.

## API·권한

기존 ADMIN 보안 패턴 아래 preview/create/status/validate/prepare/promote/rollback API를 둔다. 일반 사용자·비인증·CSRF 위반 접근을 거부한다. 비밀 아닌 snapshot token은 인증을 대체하지 않는다. 오류 응답에 DB 경로·자격증명·원문 전체를 노출하지 않는다. create/prepare/promote/rollback은 별도 상태 전이이며 UI 중복 실행을 차단한다.

## 검증 및 실제 문서 실행 게이트

1. 순수 상태/CAS/manifest 단위 TDD: stale 원본·세대·후보 변조, 중복 요청, 잘못된 문서, 누락 검증·자산, stale rollback을 거부.
2. 격리 MariaDB: 기존 4개 필드/ID/상태 보존, 후보 비노출, 중간 insert/승격/rollback 실패 주입, 동시 승격 하나만 성공.
3. 격리 Q/keyword 및 검색 통합: 후보 top-k 오염 없음, mixed generation 없음, commit 전후 요청 snapshot 일관성, Q 실패/중단/자산 소실 시 fail-closed, rollback 이후 이전 결과 복원.
4. 실제 원본 로컬 전수 검증: 17개와 source-owned proof를 검증하되 실제 품질 평가 PASS로 계산하지 않는다.
5. 전체 backend·관련 frontend·독립 고위험 리뷰, 검증된 commit/push, 공식 stage/deploy/health. 기존 main/18080/Q 프로세스 임의 종료 없음.
6. 실제 1765: fresh runtime/DB-Q/quota/network 식별→preview→비활성 후보 생성→원문/음성 검증→승인 범위 임베딩 준비/parity→새 검색 snapshot 게이트 통과→승격→health/검색·proof 확인. 어느 gate든 실패하면 기존 4개 유지.
7. 후속 penalty는 직접 원문 범위·조건 증명 후 새 고유 checkpoint만 허용. 실제 PASS 이후에만 Difficult12→holdout57→full1003. 복구 성공 자체를 전체 RAG 완료로 계산하지 않는다.

## 검토 및 다음 단계

명세 자체 점검: 강제 매핑·개수 완화 없음, 후보가 기존 top-k에 섞이지 않음, 외부 저장소를 DB 트랜잭션으로 속이지 않음, 기존 자산 보존, stale rollback 차단, 새 민감 권한 없음. 구현 전 이 서면 명세를 검토하고 승인한 뒤 단계별 실행 계획을 작성한다. 현재 제품 코드나 운영 데이터는 변경하지 않았다.
