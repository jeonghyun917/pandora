# 문서 세대 복구 구현·폐쇄 게이트 배포 — 2026-10-23

## 후속 실행: 운영 Q 기동 및 legacy baseline 호환

최신 제품 `3fdad7daea2358a4de2a391bd26b5bb304ce6510` committed/pushed `4fb074`.
사용자의 직접 기동 승인 뒤 공식 start-qdrant.cmd로 운영 Q를 기동했다(`f50f62`).
기존 C:\dev\qdrant-storage의 두 필수 collection 존재, 모든 Q 프로세스/공유 포트 부재를
확인한 뒤 시작했으며 다른 Q를 종료하지 않았다. 실측 `fcccd3`: PID17204, 공식 binary/config,
127.0.0.1:6333/6334, law_chunks211658/rag_chunks_v4 84248, green/optimizer ok,
DB-Q 개수 일치/qreadytrue/qfail0. Q binary SHA256
`911f28497e4bc962a899c1e60ee7e0cf3e59ce5f3afd9cf96a3fbaeb17ffa395`.

독립 읽기 전용 baseline 검사 `795379`에서 기존 payload에 activationStatus가 없어
identity mismatch를 확인했다. 필드별 대조 `6bcf6b`는 ID/doc/version/target/sourcePath 일치를
보였다. 기존 검색은 CANDIDATE/RETIRED를 제외하고 오래된 untagged baseline도 사용한다.
복구 보존 검사만 존재하지 않던 ACTIVE 태그를 강제 요구한 가정을 수정했다. 원래 필드 부재를
그대로 payload 지문에 포함하며 Q를 수정하거나 ACTIVE로 재분류하지 않는다. 명시 null,
CANDIDATE, RETIRED, UNKNOWN, 외래 generationId/manifest는 계속 거부한다.
최종 의미 verifier·원문 조건/범위 guard와 격리 ownership fence는 변경하지 않았다.

TDD RED `ff3488`/`1e2ef2`→Q 전체 집중 GREEN `25d9b4`/`2f7db2` exit0.
동일 수정 트리의 실제 read-only `49f73a`: 저장 baseline4/검색 subset3,
Q 보존 지문 `6a6e0bcba3fa3e32a69830a8aee3b77e305be6cd9eeb61878ddeda912b131d6a`.
이 probe 출력의 product 인자는 이전 배포 SHA였으므로 이전 배포 코드 통과로 오인하지 않는다.
검사 실행은 위 수정 working tree이며 그 동일 트리를 3fdad7d로 커밋했다.
읽기 전용 DB linkage `fc2e2d`: chunk157908/157910/157911의 model/store/point/hash/INDEXED
각각 정확 일치, matched3/searchable3/storedBaseline4. 영수증 영속화나 후보 생성은 하지 않았다.
새 코드 full `343e40`, session98837, 완료 `9e61bc`: fresh133XML/1785/f0/e0/skipped25 exit0.
종료한 격리 DB opt-in 4개가 추가 제외됐으며 DB/mapper 변경은 없다. 그 DB 경로는 이전 제품
fenced-DB 포함 full1785/skipped21 근거를 유지한다. 실제 Q integration은 이 결과에 포함하지 않는다.

현재 남은 관리자 조건은 [정확한 정책 인계 절차](qdrant-policy-handoff-20261023.md)에 있다.
Windows PowerShell의 기존 거부 스크립트는 재실행하지 않았고, 다른 엔진으로 바꾸거나 정책을
변경하지 않았다. PowerShell7의 RemoteSigned를 Windows PowerShell 허용 근거로 대체하지 않는다.
운영 Q 기동 성공은 실제 격리 검증 PASS가 아니다. 예약PAUSED/mutation=false/기존4청크 보존 유지.

새 제품 공식 staging `57b5a8`/`fbc8ac` exit0, JAR
`a5087c061b93957906203c410e59b0badf24a0e43ca9adf48e19a5cbc2f4530a`.
공식 status3c6236→공식 stop a1b931(8080 리스너 소멸 확인)→이전 supervisor 종료d5e415→
공식 deploy e6cbf0/190d97 순서이며 내부 Bypass stop 분기는 사용하지 않았다.
**새 supervisor63131 유지 / app PID85836 / runtime30323f98-6cec-4557-af84-7242626c5e4c**.
배포 후6d823b: JAR SHA 일치, Q PID17204 유지, lawDB=Q211658/ragDB=Q84248,
qreadytrue/qfail0, config e7b08ced/index e39713ce/lexical15b68153.
운영 후보·승격·외부 품질 평가 미실행. Windows PowerShell 유효 정책은6fa8dc에서
문자열 Restricted로도 직접 확인했으며 기존 차단 스크립트 재실행은 없었다.

제품: `bf04f5cc0f2d86295692cbd2b194f1bfd1c64457` (승인된 feature branch push 완료).
예약 `pandora-1003-case-release-gate`는 PAUSED로 확인했으며 재개하지 않았다.

## 구현과 검증

- 전체 corpus overlay와 base routing epoch에 결속된 불변 준비 영수증을 저장한다.
- 기존 청크·embedding 링크·공유 Q 벡터·키워드를 변경하지 않는 baseline 보존 영수증을 둔다.
- 승격은 외부 준비상태 확인 후 DB 트랜잭션에서 generation 상태·문서 pointer·전역 epoch를 CAS한다. 승격 시각도 같은 트랜잭션에 기록한다.
- 롤백은 등록된 baseline만 대상으로 별도 전체 corpus namespace를 만들고 pointer/epoch를 원자 변경한다. 실패 주입과 동시 승격에서 부분 상태가 남지 않는지 검증했다.
- 정상 ADMIN session, session-bound CSRF, same-Origin 검사와 기본 닫힘 mutation 게이트를 API/UI에 연결했다. 인증 우회와 client가 전달한 PASS는 없다.
- 일반 수집·법령 갱신/활성화는 트랜잭션 corpus revision을 변경한다. 복구 후 낡은 corpus는 새 요청에서 거부하며 일반 전체 재색인이 복구 대체 청크를 유지한 새 namespace를 CAS 게시한다.
- 표 원문 증명에도 같은 요청의 captured generation을 전달한다. 비활성 저장 상태의 승격 청크도 해당 membership으로 원본 좌표 검증을 받으며, 이전 요청은 이전 세대에 고정된다.
- cache 반환 전 활성 Q cardinality/configuration와 어휘 청크·posting·통계 cardinality를 확인한다. 반환된 후보 벡터의 point ID/model/content hash와 captured DB 본문을 대조한다. 이는 전수 vector readback 또는 임의 외부 자산 치환 방지 보장은 아니다. 준비 시 전수 readback은 별도로 유지한다.
- CONTEXT_ONLY 원본은 저장 보존 해시에 포함하되 PASS/REVIEW 검색 subset만 벡터 보존 검사에 포함한다.

독립 고위험 리뷰의 Important 5건에 대한 수정과 집중 검증을 수행했다. 별도 재리뷰를 반복하지 않았고, Minor logging 개선은 보류했다. API/운영 게이트 문서는 `docs/operations-runtime.md`에 추가했다.

최종 고정 코드 전체 backend: tool `ef3ffa`, session `4956`, 완료 `7f17bd`,
`backend-recovery-reviewed.log`; 2026-10-23 09:33:23 KST 이후 fresh XML 133개,
**1785 tests / failures 0 / errors 0 / skipped 21**, exit 0. 오래된 XML은 합산하지 않았다.
격리 MariaDB 23306의 DB/port/real datadir fencing을 포함한다. 제외는 원문 opt-in 1,
읽기 전용 mapper integration 1, 실제 recovery Q integration 1, 별도 missing-embedding repair opt-in 18이다.
앞선 1783개 통과는 리뷰 수정 전 트리이며 최종 근거로 대체했다.
프런트엔드 관련 22개 test 통과(`9a45e0`), build exit 0(`b35852`); 공식 sync로 새 정적 자산을 패키지에 포함했다.
동일 SHA의 push/배포를 이유로 전체 test를 재실행하지 않았다.
소유 격리 DB는 PID/23306/실행파일/datadir를 재확인한 뒤 정상 종료했다(`1730f9`, server session exit 0 `831c20`). 운영 DB와 app supervisor는 종료하지 않았다.

주요 RED→GREEN: baseline context-only `1d4285`→audit RED `f4ed34`/`7b8b57`→proof GREEN `6d805d`/`bd7747`;
source-proof API RED `7018fe`/`e55146`; corpus RED `910973`/`f9f64a`→GREEN `f73f69`/`40ad81`;
readiness RED `e4a9e0`/`48ade6`→집중 정상 fixture의 새 content hash 계약 반영 후 `3b7138`/`bf3a14` exit 0.
기존 테스트 기대치를 완화하지 않고 정상 Q fixture에 실제 필수 identity를 추가했으며 변조 거부 assertion도 추가했다.

## 배포와 원문 대조

- staging `99d3b9`, session `60552`, 완료 `8c4934`, exit 0.
- JAR SHA-256 `a8533833ccc33bd6e8c410e6565a0a33b49aa256617e5724f4f7d7b4cb1545e1`.
- 배포 직전 공식 status `425074`: 8080/18080/6333 listener 없음, 이전 8080 pidfile은 stale. 운영 DB 읽기 전용 `2234fa`: pandora/3306, recovery pointer table 미생성. 기존 복구 pointer가 있는 운영 환경으로 가정하지 않았다.
- 공식 배포 `6abe4e`, supervisor session **77505 유지**, PID **64024**, runtime instance **72e85956-98d7-4ee7-9a28-dfa03cb5fe62**. stop 불필요했으며 내부 Bypass stop 분기를 실행하지 않았다.
- 배포 후 `94eac1`: 8080의 PID/정적 UI HTTP 200 및 새 JS/CSS 확인. `f93c3c`: runtime JAR SHA 일치, `qdrantReady=false`, searchFailureCount 0. 공유 main/18080/Q를 시작·정지·변경하지 않았다.
- 정상 로그인 버튼만 클릭했으며 cached login 입력값/비밀번호/cookie/token을 읽거나 추출하지 않았다. 배포된 admin UI의 문서1765 미리보기에서 mutation gate 닫힘, pointer 미등록, baseline 영수증 없음, 모든 쓰기 버튼 비활성을 확인했다.
- 읽기 전용 원문 probe `e01a9b`: 기존 **4**, 재생성 **17**, source SHA `0f8cc5058d33f16d69ae558256086f07334c2480bca48a41ba2a6b5323c74893`, 제품 SHA 위와 동일, original fidelity passed=true/failures=[]. 원문 파일·운영 청크·embedding/Q/keyword 쓰기와 OpenAI 전송은 없었다. 원문 충실성 통과는 실제 답변 품질 PASS가 아니다.

## 남은 필수 작업과 차단

| 남은 항목 | 실제 차단 / 재개 조건 |
|---|---|
| 실제 격리 Q integration | 기존 Windows PowerShell 정책 거부가 미해결. 해당 검증은 mock HTTP가 아니라 전용 Q 엔진에서 후보 쓰기·누락·혼입·재시도·보존을 확인한다. 관리자에 의한 정책 준수 실행 경로가 필요하다. ExecutionPolicy 변경/Bypass/IEX/inline/다른 엔진 재실행은 하지 않았다. |
| 문서1765 실제 후보·검증·준비·승격 | 위 실제 Q 검증과 운영 Q readiness가 필요하다. 현재 6333 없음/qdrantReady=false이며 mutation 게이트를 false로 유지했다. 원본 4개는 활성 상태로 보존한다. 재개 시 Q 소유/DB-Q parity, writer 호환 상태, quota/network를 새로 확인한 뒤 정확한 SHA/manifest/revision으로 순차 수행한다. |
| 실제 복구 후 consumer/조건·범위 증명 | 복구된 원문 근거의 직접 사업범위·조건 연결을 확인해야 한다. 기관 보안을 일반 법제도 미준수로 확대하거나 수행을 발주로 자동 변환하지 않는다. 현재 근거 부족을 억지로 PASS 처리하지 않는다. |
| penalty→Difficult12→holdout57→full1003 | 실제 penalty PASS가 선행한다. 이번에는 어떤 외부 품질 checkpoint도 실행하지 않았으며 과거 terminal FAIL도 재생하지 않았다. |
| 최종 release authority·승격·완료 | 승인된 full1003 및 모든 blocking gate 0, 필요한 승격/배포 건강/인계가 완료되어야 한다. 이번 폐쇄 게이트 app 배포를 batch 승격이나 전체 완료로 계산하지 않는다. |

새 권한의 포괄적 재승인이 필요한 상태가 아니라, 실제 Q를 안전하게 검증할 수 있는 호스트 실행 조건이 필요한 상태다. 비밀번호·cookie·token 제공은 필요 없다. 완료/100% 선언 또는 예약 재개·삭제를 하지 않는다.
