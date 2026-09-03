# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-09-03T04:41:27.218Z
- K: 30
- Cases: 12/12
- Recall eligible: 12
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: f67914dca460b33a1fc44181b757927ca54f154ce841e491129ccab9a23bfbf8
- Runtime instance ID: 59eaf7e2-758c-4afa-9025-9c2a7a85631b
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 12/12 | 100.0% | 73.6% | 6/11 | 54.5% | 71.2% | 50.0% |
| lexicalHits | 7/12 | 58.3% | 43.1% | 5/11 | 45.5% | 40.9% | 33.3% |
| merged | 12/12 | 100.0% | 76.4% | 7/11 | 63.6% | 74.2% | 50.0% |
| reranked | 12/12 | 100.0% | 76.4% | 8/11 | 72.7% | 83.3% | 58.3% |
| intentFiltered | 12/12 | 100.0% | 76.4% | 5/11 | 45.5% | 65.2% | 41.7% |
| judgeCandidates | 12/12 | 100.0% | 76.4% | 5/11 | 45.5% | 65.2% | 41.7% |
| judged | 12/12 | 100.0% | 72.2% | 5/11 | 45.5% | 60.6% | 41.7% |
| selected | 12/12 | 100.0% | 72.2% | 4/11 | 36.4% | 51.5% | 33.3% |

- First drop: candidateSources=5, merged=1, survived=4, intentFiltered=2
- Request errors: 0

| ID | Recall eligible | First drop | False ground |
|---|---:|---|---:|
| egov-preliminary-review-target | yes | candidateSources | no |
| performance-measure-when | yes | candidateSources | no |
| irm-user-auth-guide | yes | merged | no |
| whistleblower-protection-scope | yes | candidateSources | no |
| noise-irm-menu-user-auth | yes | survived | no |
| privacy-integrated-guide-purpose | yes | candidateSources | no |
| pre-consultation-plan-stage | yes | intentFiltered | no |
| privacy-consent-refusal | yes | survived | no |
| whistleblower-disadvantage | yes | candidateSources | no |
| mois-national-safety-plan | yes | survived | no |
| mois-disaster-field-support | yes | intentFiltered | no |
| official-find-pipc-ai-privacy | yes | survived | no |

