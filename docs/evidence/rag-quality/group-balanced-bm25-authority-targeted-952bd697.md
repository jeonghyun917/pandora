# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-09-03T01:29:59.688Z
- K: 10
- Cases: 5/5
- Recall eligible: 5
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: fbaba9faa4294a982d0eb46acafca21e89fa99b04dbd08a23bd988dbaddd5c87
- Runtime instance ID: f14f0d2f-af6e-47dd-bc12-91942a1742a6
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 5/5 | 100.0% | 90.0% | 2/5 | 40.0% | 60.0% | 40.0% |
| lexicalHits | 4/5 | 80.0% | 80.0% | 3/5 | 60.0% | 56.7% | 20.0% |
| merged | 5/5 | 100.0% | 90.0% | 4/5 | 80.0% | 86.7% | 40.0% |
| reranked | 5/5 | 100.0% | 90.0% | 3/5 | 60.0% | 70.0% | 20.0% |
| intentFiltered | 5/5 | 100.0% | 90.0% | 2/5 | 40.0% | 60.0% | 0.0% |
| judgeCandidates | 5/5 | 100.0% | 90.0% | 2/5 | 40.0% | 60.0% | 0.0% |
| judged | 5/5 | 100.0% | 90.0% | 2/5 | 40.0% | 60.0% | 0.0% |
| selected | 5/5 | 100.0% | 90.0% | 2/5 | 40.0% | 60.0% | 0.0% |

- First drop: reranked=1, candidateSources=2, merged=1, intentFiltered=1
- Request errors: 0

| ID | Recall eligible | First drop | False ground |
|---|---:|---|---:|
| pre-consultation-when | yes | reranked | no |
| security-review-procedure | yes | candidateSources | no |
| rfp-required-items | yes | merged | no |
| pipc-pseudonym-additional-info | yes | candidateSources | no |
| pre-consultation-central-agency | yes | intentFiltered | no |

