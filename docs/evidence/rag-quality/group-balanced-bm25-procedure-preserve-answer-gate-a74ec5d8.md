# RAG Eval Gate

- Scope: targeted
- Generated at: 2026-09-03T02:26:10.767Z
- Workspace Git commit: a74ec5d87917640b084cdddb476f62027040dc6d
- Workspace Git dirty: false
- Dataset hash: 322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6
- Selection hash: b765d04ea46bd6c4cacc15a5c50b97af37c794a7292ea1245c73fe767567830f
- Index version: law_chunks+rag_chunks_v4
- Embedding model: text-embedding-3-small
- Answer model: gpt-5-mini
- Runtime artifact: jar
- Runtime artifact SHA-256: c792bc84e71acdc08123f2ea73b09dccddc3f792656c7ad6651fc72b4bc152a6
- Runtime artifact size: 67508090
- Runtime instance ID: 2aef3d21-f22c-4577-a876-078b2cc0f64e
- Runtime config SHA-256: 6db6b2f969c26a5f0dcc1d5148e84d50a1059812cee1580a0ed69f589ffe8ef6
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Lexical revision: da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa
- Baseline manifest ID: -
- Qdrant ready: true
- Qdrant search failures at start: 0
- Execution port: 8080
- Base URL: http://127.0.0.1:8080
- Total: 5
- Passed: 4
- Failed: 1
- Pass rate: 80%
- Gate passed: false
- Batch size: single request
- Failure causes: answer_verification=1
- Curated: 4/5
- Generated: 0/0
- Answer verification: 4/5

| ID | Result | Likely Cause | Next Action | Missing Terms | Missing Title | Missing Section | Missing Doc | Missing Parent | Forbidden | Missing Answer | Unsupported Claims | Top Selected |
|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| security-review-procedure | OK | Answer not grounded | Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms. | - | - | - | - | - | - | 검토 수행, 결과 통보 | 결론: 보안성검토는 정보화사업의 사업계획 단계에서 자체 보안대책을 마련한 뒤 정보보안담당관에게 1차 검토를 요청하고, 보안성검토는 1차 조치 후 국가정보원에 2차 검토를 요청하는 순서로 진행됩니다., 절차 흐름은 다음과 같습니다., 신청 및 준비: 추진사업의 사업계획 단계에서 자체 보안대책을 마련하고 정보보안담당관에게 1차 보안성 검토를 요청합니다., 조치 및 재요청: 1차 검토 결과의 취약점 보완 등 조치사항을 수행합니다., 국가정보원 검토: 조치 완료 후 국가정보원에 보안성 검토를 요청하며, 사안이 경미한 경우 2차 검토를 생략할 수 있습니다., 예외 및 추가 확인사항: 일부 단순 장비·유지보수 등은 보안성검토를 생략할 수 있으므로 자체 보안대책을 수립·시행해야 합니다., CSAP 인증이 없는 클라우드 이용은 국가정보원장과 사전협의가 필요합니다., 제출해야 할 구체적인 문서 목록과 세부 절차는 근거 문서에 명시된 제17조 등 원문에서 확인이 필요합니다. | official_doc / 행정공공기관 클라우드컴퓨팅서비스 이용안내서 / p.21 서비스를 이용할 경우 국가정보원장과 사전협의(협의 절차는 본 안내서 “4.4 국정원 보안성 검토” / p.21 |

