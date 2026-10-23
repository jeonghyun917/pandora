# 실제 격리 Q 검증: 관리자에게 필요한 최소 조치

운영 Qdrant 기동은 에이전트가 공식 `scripts/start-qdrant.cmd`로 직접 완료했다.
기존 데이터 경로는 `C:\dev\qdrant-storage`, PID17204, 6333/6334이다.
law_chunks=211658, rag_chunks_v4=84248, 둘 다 green/optimizer ok이며 앱 DB 개수와 일치한다.
기동 증거 f50f62, 건강/소유 확인 fcccd3. 공유 Q를 정지하거나 재시작하지 않는다.

## 현재 차단의 정확한 의미

실제 integration test는 `powershell.exe -NoProfile -File scripts/assert-recovery-test-qdrant.ps1`
를 통해 테스트 프로세스의 실행파일/config/리스너 소유를 검사한다. 2026-10-23 확인7c54f4에서
**Windows PowerShell**의 모든 policy scope가 Undefined라 기본 Restricted가 적용된다.
스크립트는 NotSigned이다. 별도 PowerShell 7의 RemoteSigned는 이 자식 프로세스의 허용 근거가 아니다.

Restricted에서는 관리자 실행이나 파일 서명만으로도 스크립트 실행이 허용되지 않는다.
현재 지시인 ExecutionPolicy 변경 금지까지 유지하면, 에이전트나 관리자가 붙여 넣을 수 있는
우회 없는 단일 해결 명령은 없다. Bypass/Unrestricted/Process 임시 예외/Unblock-File/IEX/
스크립트 본문의 inline 실행/다른 엔진으로 대체하는 방법을 사용하지 않는다.

## 보안 관리자에게 전달할 요청

1. 이 호스트의 **Windows PowerShell 5.1**에서 아래 두 저장소 스크립트에 대해 조직이 승인한
   정식 실행 경로를 제공한다. 에이전트가 정책을 변경하거나 자체 인증서를 만들어 신뢰시키지 않는다.
   - `C:\dev\workspace-egov\pandora\.worktrees\group-balanced-bm25\scripts\start-recovery-test-qdrant.ps1`
   - `C:\dev\workspace-egov\pandora\.worktrees\group-balanced-bm25\scripts\assert-recovery-test-qdrant.ps1`
2. 정책상 서명이 필요하면 조직이 이미 신뢰하는 코드 서명 체계로 위 파일을 배포한다.
   실행 허용은 전용 `runtime\qdrant-recovery-test-*`, 127.0.0.1:26333/26334와 기존 Q binary에만
   해당한다. 공유 6333/6334 프로세스와 데이터 경로를 바꾸는 승인이 아니다.
3. 변경이 승인·적용된 뒤 다음 읽기 전용 결과만 전달한다. 비밀번호/cookie/token은 필요 없다.

검토 대상 원본 script SHA256: start=`7196f966939c091ad7add780e394bf8f0d6c9037854b81250a4a6fb34a89b88a`,
assert=`e722a42872ecb8328846a8d68c6adbd390f2b93ce196980c9ef710fecfc0bd4c`.
이는 신뢰를 우회해 부여하는 명령이 아니라 보안 담당자의 파일 검토 식별자다.

```powershell
# Windows PowerShell 5.1 창에서 실행. 실행 정책을 바꾸는 명령이 아니다.
Get-ExecutionPolicy
Get-ExecutionPolicy -List
Get-AuthenticodeSignature 'C:\dev\workspace-egov\pandora\.worktrees\group-balanced-bm25\scripts\start-recovery-test-qdrant.ps1' |
    Select-Object Status, StatusMessage
Get-AuthenticodeSignature 'C:\dev\workspace-egov\pandora\.worktrees\group-balanced-bm25\scripts\assert-recovery-test-qdrant.ps1' |
    Select-Object Status, StatusMessage
```

그 결과가 여전히 Restricted이거나 조직이 허용할 수 없다면 그대로 중단 조건이다. 단순한
"승인한다" 답변은 호스트 정책의 적용 증거가 아니며 동일 실패를 재실행하지 않는다.

## 허용 조건이 실제로 충족된 뒤 에이전트가 할 절차

아래는 지금 사용자에게 대신 검증을 떠넘기는 절차가 아니다. 위 호스트 조건만 마련되면
에이전트가 정상 정책으로 순차 실행한다.

1. 전용 26333/26334 비어 있음, 기존 테스트 config의 절대 storage/snapshot 경로를 확인한다.
2. 승인된 원래 엔진에서 `start-recovery-test-qdrant.ps1 -TestRoot
   'C:\dev\workspace-egov\pandora\.worktrees\group-balanced-bm25\runtime\qdrant-recovery-test-20261021'`
   를 정상 실행한다. Bypass를 붙이지 않는다.
3. 실제 PID를 관측해 assert 스크립트를 정상 실행한다. 테스트 Q는 공유 포트를 소유할 수 없다.
4. `PANDORA_RECOVERY_Q_TEST_URL=http://127.0.0.1:26333`, `PANDORA_RECOVERY_Q_TEST_ROOT=위 경로`,
   `PANDORA_RECOVERY_Q_TEST_PID=실측 PID`를 해당 Maven 프로세스에만 설정하고
   `mvnw.cmd -Dtest=RagRecoveryQdrantIntegrationTests test`를 실행한다. 테스트는 합성 fixture만
   쓰고 정확히 생성한 임시 namespace만 정리한다. 운영 collection을 수정하지 않는다.
5. PASS 후 실제 source/product/DB-Q/writer/quota/network 및 baseline 보존 조건을 확인하고
   문서1765의 비활성 후보→검증→준비→승격→consumer 검증 순으로 진행한다. 그 뒤에만
   penalty PASS→Difficult12→holdout57→full1003의 고유 checkpoint로 이동한다.

예약은 PAUSED, 운영 mutation gate는 false를 유지한다. 검증 정책을 바꾸기 위한 제품 코드
수정이나 ownership fence 삭제는 하지 않는다.
# 2026-10-23 후속 상태

사용자가 에이전트의 정식 CurrentUser RemoteSigned 적용을 명시 승인했다.
실제 계정은 고정현, 프로필은 C:\Users\kaces이며 승인 호스트에서 Windows PowerShell
CurrentUser=RemoteSigned/effective=RemoteSigned를 확인했다(cfdca3).
조직 정책·LocalMachine은 변경하지 않았다. 아래의 과거 Restricted 차단은 해소됐다.

실제 격리 Q 검증은 실행됐으나 UUID baseline 컬렉션 생성에서 Gridstore os error3로 실패했다.
짧은 합성 컬렉션은 같은 서버에서 생성·삭제 성공했고, 짧은 root 및 상대경로 시도는 실패했다.
상대경로 제품 수정은 원복했다. LongPathsEnabled=0은 읽기 확인만 했으며 시스템 설정을
바꾸지 않았다. 따라서 실제 Q PASS와 문서1765 승격은 아직 아니다. 예약PAUSED 유지.

# 최신: 실제 격리 검증 통과

확장 Windows 경로 표기(`\\?\` + 검증된 전용 root)를 공식 startup의 저장·snapshot
설정 계약에 사용하자 동일 실제 integration이 exit0/1test/f0/e0/skipped0으로 통과했다
(09785c/97ff2f). OS 장경로 레지스트리는 변경하지 않았다. UUID 컬렉션이나 ownership,
원본 보존·혼입·누락 검증을 줄이지 않았다. 앞선 경로 차단 기록은 이 결과로 해소된다.
새 config는 storage_path와 snapshots_path에 같은 root의 확장 절대경로를 사용해야 한다.
