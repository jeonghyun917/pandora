# Official consequence-scope followup — 2026-10-28

Baseline product: f36d56c4e5e996c9e64f6496a329faeb15a16844; documentation HEAD before this investigation:2617dd5223278a1a1e35d910a2dd7132eecd2008. This is source investigation, not a new quality PASS or a product change. Automation remains PAUSED. No model call, failed-checkpoint replay, oracle change, source mutation or runtime change occurred.

## Question being investigated

Can a public official source directly establish consequences for unspecified general legal noncompliance in an information-system project, without importing conditions from security, public-SW procurement, pre-consultation or audit into every project?

## New primary-source findings

| Source | Confirmed scope and consequence | What it does not establish |
| --- | --- | --- |
| [행정기관 및 공공기관 정보시스템 구축·운영 지침, current](https://www.law.go.kr/LSW/admRulLsInfoP.do?admRulId=33489&efYd=0), effective20250102, seq2100000252582 | Article3 applies to administrative/public institutions' information-system projects. Article4 manages compliance. Article4-2 supports grievances. Article49(1) requires corrective action according to phased audit-result reports. Article56 delegates inspection/correction procedures to identified contract conditions. | Compliance management and grievance support do not themselves confer blanket sanction power. Article49 retains the audit-result condition; Article56 retains the applicable contract rules. Neither automatically equates an unspecified legal violation with an audit finding or contract breach. |
| [행정기본법, current](https://www.law.go.kr/lsInfoP.do?lsId=014041), effective20260319 | Article8 requires a legal basis for rights-restricting/obligation-imposing administrative action; Article22 requires the sanction's actor, grounds, type and upper bound in its underlying statute. | No specific information-system project's violation, remedy or sanction is created by these general constraints. Administrative sanctions must not be conflated with all contractual/civil/criminal consequences. |
| [행정안전부 central-department interpretation,2008](https://www.law.go.kr/LSW/cgmExpcInfoP.do?cgmExpcDatSeq=318744&mode=2&ofiClsCd=350104) | Distinguishes a legally founded corrective disposition for the original violation from disadvantage solely for noncompliance with administrative guidance. | Historical first-level interpretation, not current general authority; does not prove the proposed information-system project falls under a corrective-order statute. Not adopted as a current direct ground. |
| [Public SW supervision guide, official Seoul publication copy](https://opengov.seoul.go.kr/og/com/download.php?dname=%EB%B6%99%EC%9E%841_%EA%B3%B5%EA%B3%B5sw%EC%82%AC%EC%97%85+%EB%B2%95%EC%A0%9C%EB%8F%84+%EA%B4%80%EB%A6%AC%EA%B0%90%EB%8F%85+%EB%B0%8F+%EC%A7%80%EC%9B%90+%EA%B0%80%EC%9D%B4%EB%93%9C%28202102%29.pdf&dtype=basic&nid=23540375&rid=F0000086075049&uri=%2Ffiles%2Fdcdata%2F100024%2F20210819%2FF0000086075049.pdf) | Repeats SW Act57 supervision and improvement recommendations for the specified public SW context. | Not a new bridge from all information-system projects to public SW procurement, nor authority to upgrade a discretionary recommendation to a mandatory demand. |

The current construction/operation guideline's public HTML metadata/body were read directly (cc28cd). It remains effective20250102; a2023 search hit was not treated as current. An invalid public URL returned404(87af4f); the observed public LsInfoP/body route was used, no authentication/security denial or policy workaround.

## Stored-source comparison and query correction

The safe read-only source probe uses **exact document title**, not partial title. Partial-title lookupd3c3a2 returned0 and is not evidence of missing data. Corrected exact-title lookup1c57bf found:

- document28671, active chunk11128796, source_date20250102, effectiveCURRENT;
- stored chunk label `제1조 (10/12)` (not relabelled or forced toArticle49);
- stored content hash `0587d150969d98e3a433acdffaf706bed7d2e539f055ebaadcf47b643edc0d37`;
- actual body contains Article49's audit-result condition and corrective-action obligation.

Read69a8b6 also found the current Administrative Basic Act Article8 within document5083/chunk11154938 (`제7조의2 등`), hash `f22b33d82faca19c8eafff50b658519dcc67c917d3575760c8c404e249756753`. No forced heading mapping or source rewriting was performed.

## Independent source-freshness branch

Official current 전자정부 성과관리 지침 Article19 now includes submission of a pre-consultation application through the system and referencesArticle31's specialized institution (9ba8a3). The earlier Article18 body-equivalence result does not establish whole-document equivalence.

Before attempting a source refresh, normal sync was inspected: `LawDocumentWriter.upsertDocument` calls `LawVersionStatusService.refreshGroup` before detail/chunk readiness is established. That version service chooses the latest effective document and marks older versionsPAST; `replaceChunks` creates candidates only when that same document already has an active chunk version. A newly ingested **different source-version document** is therefore not equivalent to the already verified1765 same-document generation recovery workflow. Do not invoke normal sync as though it guaranteed inactive cross-version staging or preserved the current search snapshot. No mutation was executed and no untested new source-version lifecycle was invented during this investigation.

## Disposition and exact remaining prerequisites

1. New Article49 is a valid **audit-conditional** correction source, but not evidence that every general violation causes correction. Do not add a blanket direct-evidence/ranking/certificate bypass or force it into answer grounds.
2. The existing strict SW certificate and repair path do not require universal applicability; they preserve a literal hypothetical condition. Current actual penalty's missing proposition/condition groups cannot be filled by inventing generalized legal conclusions or changing the frozen oracle.
3. Actual penalty remains the terminal86396e8FAIL, although that call accepted the literal rewrite with unsupported[]. A source-recall-only change is not proof that this coverage failure is fixed. No new penalty call is qualified by this investigation. Difficult12→holdout57→full1003 remain dependent and unconsumed.
4. Needed external evidence: the concrete project/violated-law applicability, or an official source expressly connecting the intended broader scope to the asserted consequence. A generic permission, credential or repeated model call does not supply this evidence. This bounded investigation did not find that bridge; it does not claim no such source exists anywhere.
5. Whole-document freshness repair additionally needs a reviewed inactive **source-version** staging/promotion/rollback workflow (or the ingest owner's equivalent proven path), preserving active search and DB-Q consistency. Do not commandeer18080 or directly update CURRENT/date/source rows.

Product code and deployed artifact are unchanged. Do not rebuild/redeploy or repeat identical code tests for this documentation-only result. The current safe stopping condition is insufficient source applicability proof plus no proven non-disruptive cross-version refresh path, not missing broad user approval.
## 2025-11 guide reused in the 2026-10 official notice

[KOSA's 2026-10-02 public notice](https://www.sw.or.kr/site/sw/ex/board/View.do?bcIdx=66668&cbIdx=292&searchExt1=) republishes the MSIT/NIPA 2025-11 guide as attachment 2. The downloaded 83-page PDF is 22,784,297 bytes, SHA-256 `5e4d8b803d263310690a5b8e2ae1b625860dbc910aff2f95be0d4882df372411`. The image-only pages were inspected directly without OCR.

- Printed p. 3 (PDF p. 7): the program scope is SW projects issued by the heads of covered public entities.
- Printed p. 5 (PDF p. 9): when the review identifies noncompliance, the process sends an improvement-recommendation review opinion to the procuring body. For both notice routes, that body reviews whether to incorporate the recommendation; the procedure expressly allows nonacceptance with reasons. RFP revision follows acceptance, not every finding automatically.
- Printed p. 6 (PDF p. 10): the KRW 100 million threshold applies to the separately described semiannual publication of results, not to the preceding definition of review scope.

This supplies a dated procurement-to-recommendation process reference within public SW. It is not independent authority to compel acceptance or extend the process to every information-system project. No ingestion, oracle change, or evaluation run followed this source review.

Current-corpus check `c10bd8/ef1e8e` found no row for the exact 2025-11 file hash; the normalized title/file-name search found only December 2024 document 6. Its 198 visible v4 rows are 186 PASS and 12 CONTEXT_ONLY, all marked PENDING in the SQL chunk table. That marker is not a reliable statement of actual vector absence: read-only Qdrant lookup `2b0093` confirmed both prior-edition anchors (84723 and 84725) as `official_doc`, document 6, v4, in the live `rag_chunks_v4` collection. The new edition is absent by exact file hash; the prior edition is not wholly unindexed. No new edition was activated or substituted.
