# RAG Retrieval Recall

- Scope: targeted
- Generated at: 2026-09-03T05:17:20.596Z
- K: 30
- Cases: 57/57
- Recall eligible: 57
- No-ground cases: 0
- False grounds: 0/0
- Runtime artifact SHA-256: 73e0607138e3cfd4a83bdba9978f56d116e310313c772a442ae48ce589d5b69a
- Runtime instance ID: 7def7c0b-6aa1-4390-9bd5-16caa4aac9a4
- Index revision: 726f3c4dd53d09a99bb277ec85cae47270b7613618d23300c34a6c197eac7285
- Runtime stable through end: true

| Stage | Document hit | Hit@K | Document term coverage@K | Section/parent hit | Hit@K | Section/parent term coverage@K | Direct hit@K |
|---|---:|---:|---:|---:|---:|---:|---:|
| vectorHits | 51/57 | 89.5% | 80.1% | 30/57 | 52.6% | 71.3% | 43.9% |
| lexicalHits | 44/57 | 77.2% | 67.5% | 27/57 | 47.4% | 51.9% | 36.8% |
| merged | 54/57 | 94.7% | 84.5% | 31/57 | 54.4% | 71.6% | 42.1% |
| reranked | 56/57 | 98.2% | 88.9% | 36/57 | 63.2% | 79.4% | 50.9% |
| intentFiltered | 56/57 | 98.2% | 88.0% | 30/57 | 52.6% | 69.2% | 47.4% |
| judgeCandidates | 57/57 | 100.0% | 90.6% | 30/57 | 52.6% | 69.2% | 47.4% |
| judged | 55/57 | 96.5% | 85.4% | 28/57 | 49.1% | 58.6% | 43.9% |
| selected | 52/57 | 91.2% | 81.0% | 27/57 | 47.4% | 56.9% | 42.1% |

- First drop: judged=2, survived=19, candidateSources=28, selected=1, merged=5, intentFiltered=2
- Request errors: 0

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
| security-review-sensitive-info | yes | survived | no |
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
| public-data-open-format | yes | merged | no |
| public-data-meta-management | yes | candidateSources | no |
| law-effective-date-check | yes | candidateSources | no |
| admrul-notice-exception | yes | candidateSources | no |
| no-unrelated-privacy-for-sw | yes | merged | no |
| public-data-obligation-system | yes | survived | no |
| contract-completion-before-period | yes | survived | no |
| contract-completion-before-period-paraphrase | yes | survived | no |
| contract-completion-actual-finished | yes | survived | no |
| contract-completion-work-remaining-control | yes | survived | no |

