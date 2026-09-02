# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-09-02T10:29:22.995Z
- K: 30
- Cases: 12/12
- Recall eligible: 12
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: 84d029471157a445a2a30fef106d86a47044a208c5e1731c9943e4766abaa02e
- Runtime instance ID: 82ae43b3-b163-4311-afe4-d845a4f8b8dd
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 12/12 | 100.0% | 73.6% | 6/11 | 54.5% | 68.2% | 50.0% |
| lexicalHits | 7/12 | 58.3% | 43.1% | 5/11 | 45.5% | 50.0% | 33.3% |
| merged | 11/12 | 91.7% | 68.1% | 7/11 | 63.6% | 75.8% | 50.0% |
| reranked | 12/12 | 100.0% | 76.4% | 7/11 | 63.6% | 78.8% | 58.3% |
| intentFiltered | 12/12 | 100.0% | 76.4% | 5/11 | 45.5% | 65.2% | 41.7% |
| judgeCandidates | 12/12 | 100.0% | 76.4% | 5/11 | 45.5% | 65.2% | 41.7% |
| judged | 12/12 | 100.0% | 72.2% | 5/11 | 45.5% | 65.2% | 41.7% |
| selected | 12/12 | 100.0% | 72.2% | 4/11 | 36.4% | 56.1% | 33.3% |

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

