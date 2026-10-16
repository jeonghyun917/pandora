# 전체 RAG 품질 개선 잔여 작업 목록

기준: 2026-10-15. 진행률 추정치를 완료 증거로 사용하지 않는다. 과거 계획의 미표시 checkbox만 보고 이미 끝난 구현이나 종료된 후보 평가를 재실행하지 않는다. 최신 원문 범위 진단은 `source-list-scope-20260929.md`가 우선한다.

## 확인한 계획과 증거

- 최초 누적 범위: `docs/rag-quality-handoff-20260713.md`, `.superpowers/sdd/progress.md`.
- 전체 품질 계약: `docs/superpowers/plans/2026-07-30-rag-retrieval-evidence-shadow-migration.md`의 최종 acceptance 항목.
- 결정적 검색 계획: `docs/superpowers/plans/2026-09-01-deterministic-retrieval-promotion.md`.
- 독립 BM25 변형 계획: `docs/superpowers/plans/2026-09-02-group-balanced-bm25.md`.
- 이전 후보 종료 기록: `.superpowers/sdd/2026-07-30-rag-retrieval-evidence-shadow-migration/task-15-progress.md`.
- 새 후보 선택: `group-balanced-bm25-training-selection-b92628a5.json`의 SELECTED/eligible=true, 두 training run에서 required groups 25→27. 이 기록은 과거 artifact/dataset의 training 결과일 뿐 현재 제품 전체 승인이나 최종 품질 통과가 아니다.

## 완료 증거가 있는 부분

- 평가 provenance, 실패 분류, v4 정비, 문장별 검증과 fail-closed, 검색 계측과 독립 후보 구현은 기존 handoff와 테스트 증거가 존재한다. 이를 미구현으로 되돌려 새로 만들지 않는다.
- 2026-09-01 title-plan 후보는 NO_DOCUMENT_EXPANSION_IMPROVEMENT로 종료·복구됐다. 이후 gate를 건너뛴 것은 정해진 분기 결과다. 동일 실패 후보를 다시 실행하지 않는다.
- 제품 `960c30bb`: 명시 판단 전제와 완료될 때까지 제한 보존. 전체 backend `53045`: 1647 tests, failures/errors 0/0, skipped19. 이것은 Answer/full1003 통과 증거가 아니다.
- SNS `75581` 실제 PASS는 유지한다. 재검증 명목으로 동일 평가를 반복하지 않는다.

## 필수 잔여 항목과 순서

| 항목 | 현재 상태 | 다음 유효한 행동/선행조건 |
| --- | --- | --- |
| penalty 직접 근거와 질문 범위·조건 소유 | 미해결 | 새 source-owned 증명 경로. 일반제재 혼합·기관/SW 범위 차용·최종 verifier 완화 금지 |
| 원문 표의 열·값 소유 | 추출/청킹/의미 입력 전달 구현·검증·배포, 자연어 조건소유 미완료 | 4ca407b2·24da5cff: 명시헤더 또는 엄격한 번호형 구조만 연결, 병합구역/표전환/모호한 새헤더에서 이전소유 차단. 실제원문102개 텍스트노드 누락0, literal지원/날짜변이거부. 문서말미 단서의 특정표 적용은 별도 증명 필요 |
| 기존 RAG 코퍼스 안전 교체 | 사전검사033f4467·성공후대체정리b46b10ea 배포, 파일별트랜잭션 수정 검증, 실제교체 미실행 | REQUIRES_NEW와 신규Q ID rollback보상은 격리MariaDB 5경로/전체1674 f0e0 제외19로 검증. 실제Q보상실패·UNKNOWN commit·동시성·문서별candidate/rollback·생성제목초과 위험은 별도잔여. bulk force reimport 금지 |
| penalty 실제 평가 | 미통과 | 위 근거 문제 해결과 전체 외부 실행 fence 확인 뒤 새 고유 checkpoint; 종료 FAIL 재생 금지 |
| Difficult12 → holdout57 → full1003 | 현재 최종 제품에서 미완료 | SNS+penalty 실제 PASS 후 순차 실행. 승인 dataset hash `6b85990a6395c501138628877d4c9efda61c218afde4d0ab5a886f3588ce044c` 유지 |
| 전체 gate의 curated/answer-oracle/NO_GROUNDS·필수 claim 안전성 | 최신 최종 통과 미확인 | 최종 full1003에서 ID 완전성·중복·실패·안전 지표 확인 |
| candidate-present 직접근거 선택률 ≥80% 및 과거 two85 계약 | 직접근거 선택률 최신 증거 미확인; oracle 집합 대조 완료 | readonly76ec32에서 현재 frozen1003의 answerVerificationRequired는89개, NO_GROUNDS31개다. 과거85와 현재89를 동일 집합으로 간주하지 않는다. 최신 full1003의89개 oracle gate를 확인하며 과거 two85 재실행으로 대체하지 않는다. 추가 반복실행이 필요한지 manifest 계약을 먼저 확인 |
| 검색·의미검증 authority 승격 | 보류 | 모든 해당 shadow/독립 선택/후속 필수 gate 통과 후 승인된 별도 commit. 현재 training SELECTED만으로 승격 금지 |
| active searchable embedding backlog·DB/Q 정합성 | 현재 상태 읽기전용 확인 | `whole-rag-integrity-20261015.md`: ACTIVE embedding 누락0, 법률/행정규칙 전수감사의41건은전부RETIRED v1. 최종artifact의 동일성 유지 여부는 마지막fence에서 확인 |
| lexical 전체 coverage·parent identity | 전체coverage 검증완료, 명시parent계약 미완료 | 새READY6d3f27f9 active295906/term41924787, revision15b68153. 호스트de328f DB/Q211658+84248 일치. READ ONLY f3b16e: law96740/admrul114918/official_doc84248 각각전체누락0/hash0. 개별151도0cfb0a로검증. law96641/admrul114866의빈source_parent는index와동일하지만명시소유증명아니므로별도계약검토필요. 하드코딩helper사용안함/추가빌드금지 |
| 짧은 청크 감사·검토 | 읽기전용 자동 감사 실행, 검토 미완료 | 법령/규칙252 중manual98, INDEXED RAG5269 중manual761 등 자동후보. 실제 source context로 검토해야 하며 자동추천을 바로 제외·삭제로 적용하지 않음 |
| 관리자 parity 지표 | 수정·검증·배포 완료 | 제품a2868eb8/전체1648 f0e0 제외19/실제UI Qdelta0. 활성범위 분모만 수정했고 raw 전체현황은보존 |
| 최종 배포·rollback·인계 | 미완료 | gate 통과 제품의 공식 8080 smoke, flag/version/revision/JAR 기록, 최종 인계. 18080/main/output 불변 |

## 실행 규칙

현재 단일 penalty 분기가 막혀도 기존 증거의 잔여 계약 대조와 독립 읽기전용 검사는 수행한다. 새 설계나 관계없는 재작성은 추가하지 않는다. 같은 SHA의 이미 통과한 검증을 commit/push/배포라는 이유만으로 반복하지 않는다. 로컬 guard의 통과, 문서 기록, 또는 안전한 거부만으로 전체 100%라고 하지 않는다. 필수 항목을 임의 제외하거나 보류 완료 처리하지 않는다.

읽기전용76ec32는1003개/고유ID1003개, 승인 dataset hash와 selection hash `cf509e5930230af80b6efdd17ffa37ce5ff3113456208b3f283c5b4b5faa511d` 일치를 확인했다. OpenAI0이며 평가 결과를 산출한 실행은 아니다.
