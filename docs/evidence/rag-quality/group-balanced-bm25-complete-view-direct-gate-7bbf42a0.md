# RAG Eval Gate

- Scope: targeted
- Generated at: 2026-09-03T04:21:22.905Z
- Workspace Git commit: 7bbf42a0dc6237dfbec5932101fb1b92d5db68e0
- Workspace Git dirty: true
- Dataset hash: 322dee52b2c78576754fe726ac3611a8b677bec9cbe7697684f9c1172d0308a6
- Selection hash: d178fb442703eeed03d110caab2512cb661d63ac082ade7596cd5554ee83d278
- Index version: law_chunks+rag_chunks_v4
- Embedding model: text-embedding-3-small
- Answer model: gpt-5-mini
- Runtime artifact: jar
- Runtime artifact SHA-256: 3788ef016431a527120627a86aa7e7ff5b314cc035d5b09bb7480387eff06ced
- Runtime artifact size: 67515641
- Runtime instance ID: 7bb9501d-f76b-4a97-b76d-46beadf83c53
- Runtime config SHA-256: 6db6b2f969c26a5f0dcc1d5148e84d50a1059812cee1580a0ed69f589ffe8ef6
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Lexical revision: da8d51cecea3bd10ce9ba7eb40c2a25015d2166e983d836018616377de9bb9aa
- Baseline manifest ID: -
- Qdrant ready: true
- Qdrant search failures at start: 0
- Execution port: 8080
- Base URL: http://127.0.0.1:8080
- Total: 1
- Passed: 0
- Failed: 1
- Pass rate: 0%
- Gate passed: false
- Batch size: single request
- Failure causes: answer_verification=1
- Curated: 0/1
- Generated: 0/0
- Answer verification: 0/1

| ID | Result | Likely Cause | Next Action | Missing Terms | Missing Title | Missing Section | Missing Doc | Missing Parent | Forbidden | Missing Answer | Unsupported Claims | Top Selected |
|---|---:|---|---|---|---|---|---|---|---|---|---|---|
| security-review-procedure | OK | Answer not grounded | Inspect generated answer, ClaimVerifier unsupported claims, and answer-level expected terms. | - | - | - | - | - | - | 결과 통보 | - | official_doc / (붙임2) 2026년 정보화사업 보안성 검토 가이드 / p.2 ‣보안성 검토 대상 사업 식별 / p.2 |

