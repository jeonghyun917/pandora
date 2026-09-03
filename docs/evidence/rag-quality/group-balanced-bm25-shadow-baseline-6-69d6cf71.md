# RAG Eval Gate

- Scope: targeted
- Generated at: 2026-09-03T06:04:50.942Z
- Workspace Git commit: 69d6cf71ea29f90193fb47778491c309af3604a2
- Workspace Git dirty: false
- Dataset hash: 322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6
- Selection hash: cfab08f93f74089e00982c05f8f5a2b15a6624f3fb126e7f7e59628e2fd0ca66
- Index version: law_chunks+rag_chunks_v4
- Embedding model: text-embedding-3-small
- Answer model: gpt-5-mini
- Runtime artifact: jar
- Runtime artifact SHA-256: ca411d8f3a2442b541759b2ae24b61617d808134c9bf456903cf9e1f06b93a6c
- Runtime artifact size: 67517363
- Runtime instance ID: 0bb5ba2f-55e8-4385-a3a9-087a9bb488c6
- Runtime config SHA-256: e7b08ced10e7fd56f1dbfda7822dccb019aec056cf31ea16fca24604cbd1576a
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Lexical revision: da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa
- Baseline manifest ID: -
- Qdrant ready: true
- Qdrant search failures at start: 0
- Execution port: 8080
- Base URL: http://127.0.0.1:8080
- Total: 6
- Passed: 1
- Failed: 5
- Pass rate: 17%
- Gate passed: false
- Batch size: 1
- Failure causes: no_grounds=1, answer_verification=4
- Curated: 1/6
- Generated: 0/0
- Answer verification: 1/6

| ID | Result | Likely Cause | Next Action | Missing Terms | Missing Title | Missing Section | Missing Doc | Missing Parent | Forbidden | Missing Answer | Unsupported Claims | Top Selected |
|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| project-review-sns-operation | NO_GROUNDS | No direct grounds | Check retrieval recall first; if candidates exist, inspect EvidenceJudge rejection. | 국가기관 등이 발주하는 모든 SW사업, 국가기관등의 장이 발주하는 소프트웨어사업, 소프트웨어사업, 대상사업 | 과업심의, 공공SW사업 | target_scope | 공공소프트웨어사업 과업심의, 공공SW사업 법제도 관리감독 | 대상 사업 | - | - | - | - |
| project-review-pre-consultation-relation | OK | Answer not grounded | Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms. | - | - | - | - | - | - | 과업심의와 사전협의는 별도 제도\|서로 다른 제도, 각 제도의 대상사업 여부\|과업심의 대상과 사전협의 대상을 각각 확인 | 결론: 과업심의는 소프트웨어사업의 대상사업 여부를 기준으로 판단합니다., 대상사업은 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)으로, 소프트웨어의 개발·제작·생산·유통·운영·유지관리 및 소프트웨어 관련 서비스가 해당됩니다., 비대상 예외로 단순 H/W 도입·설치(어플라이언스 포함), 단순 동영상 제작, 네트워크 등 인프라 수수료 등은 소프트웨어사업으로 볼 수 없어 과업심의 대상이 아닙니다., 사전협의는 대상기관이 추진하는 모든 정보화사업을 기준으로 판단합니다., 금액기준 등 제외조건이 있어 중앙·공공기관은 10억원 미만, 광역·공기업 2억원 미만, 기초·공기업 1억원 미만 사업은 제외되며, 신규홈페이지·SW개발비 2억원 이상 등 예외 항목은 여전히 대상입니다., 구체적 적용 여부는 사업성격과 금액 기준을 확인해야 합니다. | official_doc / 공공소프트웨어사업 과업심의 가이드(2022. 12.) / p.5 적용 대상 사업 / p.5 |
| pre-consultation-exception | OK | Answer not grounded | Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms. | - | - | - | - | - | - | 기관별 사업금액 기준 미만인 사업은 제외\|기관별 기준금액 미만 사업은 사전협의 제외, 신규 사업은 금액 기준 미만이어도 사전협의 대상\|신규로 추진하는 사업은 대상에 포함 | - 제외대상은 사업금액 기준 미만 사업으로, 중앙·공공기관은 10억원 미만, 광역·공기업은 2억원 미만, 기초·공기업은 1억원 미만인 사업입니다., 제외대상은 기관 자체 수입으로 추진하는 정보화사업입니다., 다음은 사업금액과 상관없이 제외에서 제외되어 사전협의 대상에 포함됩니다, BPR/ISP/ISMP 등 정보시스템 구축 목적의 사업, ISP 등의 결과에 따른 구축 사업, 신규 홈페이지·모바일 앱 구축을 포함한 사업, SW개발비가 2억원 이상인 사업, 클라우드 전환사업. | official_doc / 2024년 정보화사업 사전협의 안내자료(배포용) / p.28 대상 사업 / p.28 |
| security-review-target | OK | Answer not grounded | Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms. | - | - | - | - | - | - | 정보시스템 구축\|민감정보 처리\|주요정보통신기반시설 | - | official_doc / (붙임2) 2026년 정보화사업 보안성 검토 가이드 / p.2 검토 대상 / p.2 |
| security-review-exception | OK | Answer not grounded | Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms. | - | - | - | - | - | - | 참여 인력이 시스템에 접근하지 않으면 보안성검토 대상에서 제외\|시스템에 접근하지 않는 DB 구축·콘텐츠 제작 용역은 검토 제외, 참여 인력이 시스템에 접근하면 보안성검토 대상\|시스템 접근이 있으면 검토 대상 | 결론: 제공된 근거만으로는 보안성검토를 생략할 수 있는 경우를 확인할 수 없습니다., 근거 문서들에서 검토 대상 목록은 제시되어 있으나, "보안성 검토 절차 이행 생략 대상"의 구체 항목은 제공된 내용에 포함되어 있지 않습니다., 생략 기준이 적힌 해당 지침의 관련 조항 전문 또는 "보안성 검토 절차 이행 생략 대상" 목록을 제출해 주시면 생략 가능 여부를 판단해 드리겠습니다. | admrul / 대검찰청 정보보안 기본지침 / 제1조(목적) (2/33) |

