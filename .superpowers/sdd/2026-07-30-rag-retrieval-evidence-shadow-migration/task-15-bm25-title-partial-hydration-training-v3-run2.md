# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-08-31T10:19:14.092Z
- K: 30
- Cases: 24/24
- Recall eligible: 24
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: 4ee6018b5c76baa2cda8e2896bfa6e6beeadfe0b75b3187e53faaab972de6ceb
- Runtime instance ID: 389e1c20-157e-4914-aa0e-6c9970b9a39c
- Index revision: a9c811046e180cc172f879a15f65b54d891279178b87263350f5dfef88b19546
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 23/24 | 95.8% | 92.4% | 11/24 | 45.8% | 66.0% | 41.7% |
| lexicalHits | 16/24 | 66.7% | 62.5% | 11/24 | 45.8% | 47.9% | 33.3% |
| merged | 24/24 | 100.0% | 92.4% | 14/24 | 58.3% | 72.9% | 50.0% |
| reranked | 24/24 | 100.0% | 94.4% | 14/24 | 58.3% | 77.1% | 45.8% |
| intentFiltered | 24/24 | 100.0% | 94.4% | 13/24 | 54.2% | 69.4% | 45.8% |
| judgeCandidates | 24/24 | 100.0% | 94.4% | 13/24 | 54.2% | 69.4% | 45.8% |
| judged | 24/24 | 100.0% | 92.4% | 12/24 | 50.0% | 59.7% | 41.7% |
| selected | 24/24 | 100.0% | 92.4% | 12/24 | 50.0% | 57.6% | 41.7% |

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

