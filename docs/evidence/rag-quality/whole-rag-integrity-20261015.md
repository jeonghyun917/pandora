# 전체 RAG 잔여 작업: 읽기 전용 무결성 점검

## 고정 경계

- 제품: `960c30bb`, JAR `79d36d7777bc862e71c532fa0f583093a8574a0d8e2cb413a47da7ddd9350119`.
- 점검 런타임: `d79bf701-4808-41d6-a5d0-e080fae42ef9`, index `e39713ce571b7502f930a6efe517c93ccc63d3147d04545314085d3e60d95fa5`.
- 모든 관리자 감사 페이지에서 동일 runtime/index 및 전진 커서를 확인했다. GET 조회만 수행했으며 DB/Qdrant 쓰기·OpenAI 호출·승격·재색인은 없다.

## 승인 데이터셋 서버/로컬 대조

읽기 전용 `42437f`: 양쪽1003개, 서버 고유ID1003개, answer oracle89개. 질문, answerVerificationRequired, requiredPropositionGroups, requiredConditionGroups, forbiddenAnswerTerms의 drift0. 이는 실제 평가 PASS가 아니라 평가 입력 계약의 일치다.

## 관리자 감사 접근

CLI 감사 `fc6db1`은 최초 runtime metadata 검증에서 중단됐다. 직접 공용 runtime-info 조회 `d04ee5`/`00011b`는 동일 런타임 필드를 정상 확인했다. 관리자 토큰을 사용한 비교 진단은 자동 보안 검토가 거부했으므로 재실행·환경값 읽기·추출·우회하지 않았다.

별도로 사용자가 명시 승인한 정상 로그인 버튼만 클릭했다. 캐시된 입력은 도구에서 redacted였으며 값을 읽거나 추출하지 않았다. 정상 로그인 세션의 브라우저 탐색으로 관리자 GET 감사 화면에 접근했다. 쿠키·토큰을 추출하거나 다른 도구로 전달하지 않았다.

## 법률·행정규칙 전수 GET 감사

| 대상 | 페이지 | 감사 행 | 보고 이슈 | 원인 | 문서 |
| --- | ---: | ---: | ---: | --- | ---: |
| law | 11 | 96770 | 30 | QDRANT_POINT_MISSING | 2808 |
| admrul | 12 | 114929 | 11 | QDRANT_POINT_MISSING | 18109 |

법률 첫 페이지1000행, 이후10000행씩 커서 진행, 마지막5770행. 행정규칙은10000행씩 진행, 마지막4929행. 각 마지막 페이지는 limit 미만이어서 범위 종료를 확인했다.

읽기 전용 DB 소유 대조 `8a0548`:

- doc2808 v1: RETIRED30, IDs11115787–11115816. 감사30건과 정확히 일치. v2 ACTIVE99, IDs11294157–11294255.
- doc18109 v1: RETIRED11, IDs11171530–11171540. 감사11건과 정확히 일치. v2 ACTIVE52, IDs11294105–11294156.
- 따라서 보고된41건은 ACTIVE point 누락이 아니다. 퇴역 행을 삭제하거나 재임베딩하지 않는다. 모든 보고 이슈가 이41건이라는 범위에서 현재 ACTIVE 행의 point 누락은0이다. 원문 의미 품질이나 전체 답변 품질 통과를 뜻하지 않는다.

## 별개의 lexical coverage 미완료

읽기 전용 `03b555`:

| 대상 | 현재 검색가능 청크 | READY lexical 누락 | 본문 해시 불일치 |
| --- | ---: | ---: | ---: |
| admrul | 114918 | 52 | 0 |
| law | 96740 | 99 | 0 |
| official_doc | 84248 | 0 | 0 |

누락은 doc18109 v2의52개와 doc2808 v2의99개다. source parent_key가 비어 있는 법률/규칙114866/96641개는 lexical의 값과 동일하며, 이 수치를 새 색인 손상이나 parent 소유 증명으로 단정하지 않는다. explicit parent 계약은 별도 미완료다.

`dadd7d`: 이전 빌드6dca282f…는FAILED/부분55500, 기존 READY98a3de8a…295796 유지. 최신 대조는 이전601초 InnoDB double-write flush FATAL와 중단 빌드의 정상 FAILED 처리 이력(최신 범위 문서)을 재확인한 것이다. 백업·안정성 선행조건 없이 대량 빌드 재생/신규 재시도하지 않는다.

## 관리자 정합성 지표의 최소 수정

관리자 화면은 법령-30/행정규칙-11/공식문서0, 합41을 표시했다. `AdminOverviewService.indexedLawChunks`는 ACTIVE 조건 없이 INDEXED 행을 집계했다. 실제 ACTIVE parity와 다른 분모였다.

- `AdminOverviewIndexedScopeTests` RED58190: ACTIVE 조건이 없는 실제 생성 SQL에서1FAIL.
- 수정 범위: `indexedLawChunks`의 SELECT에 `c.activation_status = 'ACTIVE'` 한 줄 추가. 원문/embedding/Qdrant 삭제·변경 없음, 원래 INDEXED/hash/use/target 조건 유지.
- 집중32341 exit0/1PASS, 전체72176 exit0/1648 tests failures0 errors0 skipped19.
- 실제 수정 메소드의 읽기 전용 SQL 대조 `cfaa28`: law96740/admrul114918. Qdrant exact count `c6e859`도 각각96740/114918이고 audit 종료 후 runtime/index 불변·qreadytrue/qfail0이다.
- `9d3084`: 현재 검색가능 law96740/admrul114918/official_doc84248 각각 현재 model/store/hash 일치 INDEXED embedding 누락0. 이 결과는 UI의 전체 대기5159나 source parent 계약을 임의 완료 처리하지 않는다.
- 범위 리뷰: 한 SELECT의 ACTIVE 조건 한 줄과 DB 경계 query contract 회귀만 변경. 기존 use/target/model/store/hash/INDEXED 조건 유지, 공식문서·pipeline 전체 집계·판정/답변/데이터셋/authority 불변. 이 변경은 RAG 답변 행동 변경이 아니므로 별도 Answer 평가를 실행하지 않았다. 전체1003 gate는 미실행이다. 배포는 아직 별도 단계다.

## 배포와 운영 화면 확인

- 제품 `a2868eb8` pushed. 공식stage39103 exit0/JAR `a235c2132da495a9e2e40dabe208caf472440c2bd39281ff3bb265f201bcbd0e`.
- 호스트status와PID소유 확인 후 공식stop3546d2는8080 PID3984만 종료. 리스너없음 확인 후 공식deploy12322 KEEPALIVE/PID31684/runtime `021d9795-d691-44db-9129-a998764c55d5`.
- fresh smoke `f832c3`: law DB=Q211658/rag DB=Q84248/qreadytrue/qfail0. config/index/lexical은 앞선 감사와 동일. Q13672/18080/main/output 불변.
- 정상 로그인 버튼만으로 재로그인한 실제 관리자 화면에서 `Qdrant 정합성 0 / 법령0,행정규칙0,공식문서0` 확인. pipeline 전체 INDEXED295947 등 원래 raw 현황은 변경하지 않음. 새 집계0을 전체 품질 완료로 간주하지 않는다.
- 백업 자격 설정 존재 여부만 확인한 `5c9920`: PANDORA_DB_PASSWORD 미설정. 값 읽기·추측·추출·대량 DB backup 또는 재색인은 수행하지 않았다. 해당 선행조건은 여전히 필요하다.

## 기존 짧은 청크 감사의 읽기 전용 실행

- `c8cc2d`/`9236a7`: residual-tiny252개 분류. meaningful71, ambiguous/manual98, navigation51, image32. 자동 추천이며 실제 제외/수동검토 완료가 아니다.
- `1741be`/`17609e`: 기존 RAG short audit5269개(현재 INDEXED 범위) 분류. keep4243/manual761/suppress-or-downrank61/merge-or-downrank201/context-only3. applyRequested/dbApplyCompleted/vectorCleanupCompleted는 모두false.
- 두 스크립트의 조회 범위는 서로 다르고, 기존 legacy 쿼리의 short 전체10428과 동일한 검색가능 집합이라고 단정하지 않는다. 원문·quality status·embedding·Qpoint를 수정하지 않았다. 자동 분류를 최종 사람 검토로 위장하거나 권고만으로 청크를 제외하지 않는다.

penalty 실제 PASS와 후속 release ladder는 여전히 미완료다.

## 짧은 명시 본문 누락과 백업 자격 정정

- 실제 doc5792의 명시 본문은37자 STRING이지만 기존 파서는 일반80자 필터로 sections0을 반환했다(1b395e). 일반 수치 고시 fixture RED12795 1FAIL로 재현했다.
- 정확한 `$.AdmRulService.조문내용`의 짧은 scalar만 기존 structured helper로 보존한다. 다른 경로의 같은 필드명·짧은 메타·공백은 여전히 제외하며 일반80자 필터와 긴 본문 경로는 변경하지 않았다.
- 집중6504 57PASS; 최종전체82068 exit0/1650 tests failures0 errors0 skipped19. 실제 SELECT 전용 bef1bd에서 sections1/37자 원문과1.0161 보존 확인. 기존 DB 청크/후보/embedding/승격 변경 없음. 외부 Answer 평가 미실행.
- 사용자가 기존 DB 자격 설정을 지적했다. 공식 application.properties의 중첩 datasource 환경변수 fallback을 값 출력 없이 정상 해석해 공식 backup-mariadb.ps1에 메모리상 전달했다. PANDORA_DB_PASSWORD 미설정만으로 자격이 없다고 판단한 앞선 기록을 정정한다. 비밀번호 추측·채팅 전송·토큰/쿠키 추출은 없다.
- backup40536 진행 중이며 f9d87f 호스트 파일5.6GB 증가를 확인했다. 종료코드/완결성 확인 전 백업 성공 또는 대량재색인 준비 완료로 간주하지 않는다.
- 제품2bcef88e pushed/전체1650 f0e0 제외19. stage99554 exit0/JAR73f05577…513d8, 소유31684만 공식stop4847c9 후 deploy26516 KEEPALIVE PID56516/runtime8c053ce1-a13c-4c26-ad24-30a957e11190. 5b6eec에서 DB/Q parity와config/index/lexical 불변 확인. 실제 정상로그인 UI preview5792도 projected0→1,37자전체/수치/손실0 확인. 후보생성·임베딩·활성화 없이 종료했다.
