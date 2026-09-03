# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-09-03T04:55:45.098Z
- K: 30
- Cases: 56/57
- Recall eligible: 56
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: f67914dca460b33a1fc44181b757927ca54f154ce841e491129ccab9a23bfbf8
- Runtime instance ID: 59eaf7e2-758c-4afa-9025-9c2a7a85631b
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 50/56 | 89.3% | 79.8% | 29/56 | 51.8% | 71.7% | 39.3% |
| lexicalHits | 44/56 | 78.6% | 68.8% | 27/56 | 48.2% | 52.8% | 37.5% |
| merged | 53/56 | 94.6% | 84.2% | 29/56 | 51.8% | 71.1% | 41.1% |
| reranked | 55/56 | 98.2% | 88.7% | 35/56 | 62.5% | 76.9% | 50.0% |
| intentFiltered | 55/56 | 98.2% | 87.8% | 29/56 | 51.8% | 68.6% | 46.4% |
| judgeCandidates | 56/56 | 100.0% | 90.5% | 29/56 | 51.8% | 68.6% | 46.4% |
| judged | 53/56 | 94.6% | 83.3% | 27/56 | 48.2% | 57.9% | 42.9% |
| selected | 50/56 | 89.3% | 78.9% | 26/56 | 46.4% | 56.1% | 41.1% |

- First drop: judged=2, survived=18, candidateSources=29, selected=1, merged=4, intentFiltered=2
- Request errors: 1

| ID | Recall eligible | First drop | False ground |
|---|---:|---|---:|
| project-review-sns-operation | yes | judged | no |
| project-review-pre-consultation-relation | yes | survived | no |
| it-compliance-penalty | yes | candidateSources | no |
| official-doc-title | yes | survived | no |
| noise-unification-white-paper-header | yes | candidateSources | no |
| public-data-custom-support | yes | survived | no |
| public-data-preprocessing | yes | selected | no |
| mois-autonomy-preconsultation-target | yes | merged | no |
| mcst-tourism-dure-support | yes | candidateSources | no |
| pipc-cctv-retention-period | yes | candidateSources | no |
| public-data-portal-standard-scope | yes | candidateSources | no |
| mcst-tourism-dure-period | yes | candidateSources | no |
| project-review-all-sw-projects | yes | survived | no |
| project-review-exclusion-hardware | yes | survived | no |
| pre-consultation-public-agency | yes | survived | no |
| security-review-notice-result | yes | candidateSources | no |
| rfp-requirement-evaluation | yes | survived | no |
| commercial-sw-direct-buy-exception | yes | survived | no |
| procurement-digital-service-mall | yes | candidateSources | no |
| cctv-public-place-rule | yes | survived | no |
| cctv-retention-not-fixed-30 | yes | candidateSources | no |
| traffic-right-turn-pedestrian | yes | candidateSources | no |
| irm-faithfulness-meaning | yes | candidateSources | no |
| mois-autonomy-document-confusion | yes | merged | no |
| project-review-maintenance-check | yes | survived | no |
| project-review-scope-change | yes | judged | no |
| pre-consultation-central-agency | yes | intentFiltered | no |
| pre-consultation-excluded-project | yes | candidateSources | no |
| security-review-major-infra | yes | survived | no |
| security-review-skip-condition | yes | candidateSources | no |
| rfp-requirement-method | yes | survived | no |
| commercial-sw-direct-buy-target | yes | survived | no |
| procurement-catalog-vs-contract | yes | candidateSources | no |
| public-data-portal-manual-application | yes | candidateSources | no |
| privacy-consent-items-law | yes | candidateSources | no |
| privacy-processing-principle | yes | candidateSources | no |
| pseudonym-extra-info-separate | yes | candidateSources | no |
| traffic-right-turn-stop-rule | yes | candidateSources | no |
| whistleblower-protection-action | yes | candidateSources | no |
| irm-measure-period | yes | candidateSources | no |
| mois-autonomy-request-docs | yes | merged | no |
| privacy-retention-notice | yes | candidateSources | no |
| privacy-minimum-collection | yes | candidateSources | no |
| privacy-destruction-principle | yes | candidateSources | no |
| cctv-install-purpose-limit | yes | intentFiltered | no |
| cctv-retention-period | yes | candidateSources | no |
| public-data-open-format | yes | candidateSources | no |
| public-data-meta-management | yes | candidateSources | no |
| law-effective-date-check | yes | candidateSources | no |
| admrul-notice-exception | yes | candidateSources | no |
| no-unrelated-privacy-for-sw | yes | merged | no |
| public-data-obligation-system | yes | survived | no |
| contract-completion-before-period | yes | survived | no |
| contract-completion-before-period-paraphrase | yes | survived | no |
| contract-completion-actual-finished | yes | survived | no |
| contract-completion-work-remaining-control | yes | survived | no |

