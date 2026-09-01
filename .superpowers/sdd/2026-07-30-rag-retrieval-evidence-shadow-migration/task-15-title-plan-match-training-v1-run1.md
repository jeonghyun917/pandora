# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-09-01T07:12:25.650Z
- K: 30
- Cases: 24/24
- Recall eligible: 24
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: 7ebac764aed6626cb12df0a8d1ea2ef9be57ea66e3766ae31de361506790c24f
- Runtime instance ID: 760d5bc3-63ab-492d-932c-7592043a044c
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 23/24 | 95.8% | 92.4% | 11/24 | 45.8% | 66.0% | 41.7% |
| lexicalHits | 16/24 | 66.7% | 62.5% | 11/24 | 45.8% | 47.9% | 33.3% |
| merged | 24/24 | 100.0% | 92.4% | 14/24 | 58.3% | 72.9% | 50.0% |
| reranked | 24/24 | 100.0% | 94.4% | 15/24 | 62.5% | 77.1% | 50.0% |
| intentFiltered | 24/24 | 100.0% | 94.4% | 14/24 | 58.3% | 69.4% | 50.0% |
| judgeCandidates | 24/24 | 100.0% | 94.4% | 14/24 | 58.3% | 69.4% | 50.0% |
| judged | 24/24 | 100.0% | 92.4% | 13/24 | 54.2% | 59.7% | 45.8% |
| selected | 24/24 | 100.0% | 92.4% | 13/24 | 54.2% | 57.6% | 45.8% |

- First drop: survived=9, reranked=2, candidateSources=11, intentFiltered=1, merged=1
- Request errors: 0

| ID | Recall eligible | First drop | False ground |
|---|---:|---|---:|
| project-review-target | yes | survived | no |
| project-review-simple-software | yes | survived | no |
| project-review-hardware-exclusion | yes | survived | no |
| pre-consultation-target | yes | survived | no |
| pre-consultation-when | yes | reranked | no |
| pre-consultation-exception | yes | candidateSources | no |
| security-review-target | yes | survived | no |
| security-review-exception | yes | candidateSources | no |
| security-review-procedure | yes | candidateSources | no |
| rfp-required-items | yes | intentFiltered | no |
| rfp-tech-score-table | yes | survived | no |
| public-data-db-standard | yes | candidateSources | no |
| procurement-catalog-contract | yes | candidateSources | no |
| commercial-sw-direct-purchase | yes | survived | no |
| irm-faithfulness | yes | candidateSources | no |
| traffic-crosswalk-stop | yes | candidateSources | no |
| video-cctv-guide | yes | reranked | no |
| personal-info-purpose | yes | candidateSources | no |
| privacy-consent-notice-items | yes | survived | no |
| mois-autonomy-preconsultation-procedure | yes | merged | no |
| pipc-cctv-public-place-exception | yes | survived | no |
| pipc-pseudonym-additional-info | yes | candidateSources | no |
| msit-tving-investigation | yes | candidateSources | no |
| ai-law-enforcement-date | yes | candidateSources | no |

