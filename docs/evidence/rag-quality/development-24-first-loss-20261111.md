# Development 24: applicable sources and first observed loss

This is an audit of the saved **search** checkpoint, not a rerun, a current-build score, or an answer-quality PASS. The exact artifact is `current-24-5ec2565-doc16-20261111-052944Z.json`, product `5ec2565342f1415118a5ee1a549f1cfe7172f808`, runtime `0a197db8-e9c3-4700-9599-632164beeccc`, JAR `0c8f822624b64d0026472bfbff4f46393ef848857a1f4afb09f4e579be5b48fe`. The artifact reports stable runtime identity, 24 attempted, 22 responses, two HTTP 500s, and `complete=false`.

- Dataset: `6b85990a6395c501138628877d4c9efda61c218afde4d0ab5a886f3588ce044c`; selection: `4e5546b95f079888138e7ef5d921b2a77c7f309aad16f2d269e8b83b9908bf22`.
- Vector: `e39713ce571b7502f930a6efe517c93ccc63d3147d04545314085d3e60d95fa5`; lexical: `4500a4da1ff54f0f25c95119a6a2647ae51381c0b4d31b52376344603e48f665`; runtime config: `47d394b4ea5aa5261c62f9c7343a7963f10b0bd41efc1637f6b101ae302b5c32`.
- Match individual candidate keys and inspected source scope, not the aggregate keyword-based `firstDropStage`. “Not observed” means absent from the bounded trace, not absent from the corpus. A request error has no rank and is not imputed as rank zero.

| Case | Source boundary | Saved checkpoint disposition |
| --- | --- | --- |
| project-review-target | 84023: agency-issued SW project scope | Selected |
| project-review-simple-software | 84023: commercial SW within a qualifying project | Selected |
| project-review-hardware-exclusion | 84023: hardware only when not a SW project | Selected; earlier failed answer is separate; PDF proof was disabled in this checkpoint |
| pre-consultation-target | 87028: covered institutions and projects | Selected |
| pre-consultation-when | 87020: timing under §83 | Selected |
| pre-consultation-exception | 87006: guide budget exceptions and new-project inclusion, not universal applicability | Selected |
| security-review-target | 177614: current reviewed §15 scope | First lost at judge; separate later checkpoint recovered this source |
| security-review-exception | 177615: §16 omission with conditions, not the frozen system-access distinction | HTTP 500 before rank; later independent local recovery only |
| security-review-procedure | 177480: §17 submission; not §18 post-notice action | HTTP 500 before rank; later independent local recovery only |
| rfp-required-items | 85101/85107: requested items and scoped procedure | Both selected |
| rfp-tech-score-table | 11232658/11128791: scoped criteria and scoring limits, not a universal table duty | Neither observed in bounded trace; direct universal requirement not proved |
| public-data-db-standard | 11168239: applicable database standard | Selected |
| procurement-catalog-contract | 11164620: §10 catalog route, not commercial direct-purchase exemption | Selected |
| commercial-sw-direct-purchase | 11294125/11294126: §7 scope and explicitly linked §8 exception | §7 selected; §8 first lost at judge. Later `3a46932` recovered §8 as bounded support in local context, not a new online score |
| irm-faithfulness | 164487: metric definition; formula is separate | Definition selected |
| traffic-crosswalk-stop | 10763391: Art.27(1) actor/action/condition | Selected |
| video-cctv-guide | 116900: guide purpose/public-institution scope | Selected |
| personal-info-purpose | 10938385: Art.30; consent Art.15 is separate | Selected |
| privacy-consent-notice-items | 10938372: Art.15 notice items | Selected |
| mois-autonomy-preconsultation-procedure | 102565/102566 and 127625/127626: equivalent saved flows across editions | 102566 selected; other three lost at grounds. Equivalent content is not missing procedure |
| pipc-cctv-public-place-exception | 144970: named guide exception | Selected |
| pipc-pseudonym-additional-info | 120685/10930147: storage, not access exception | Both selected |
| msit-tving-investigation | 92108: actual report, not metadata label | Selected |
| ai-law-enforcement-date | 11058178: official detail anchor; operative article alone is not date proof | First lost at grounds; later typed date recovery and frozen as-of conflict are separate |

At least one scoped anchor was selected in **19 of 22 responses**; this is not complete-answer coverage. The independently paired seven-anchor Recall@20 results remain vector 4/7 and BM25 3/7. Do not substitute this table for a new checkpoint, use it to infer a current-build answer PASS, or advance the evaluation ladder without an eligible penalty PASS. The [release evidence ledger](source-list-scope-20260929.md) records later bounded fixes, exact runtime identities, and release prerequisites.

### RFP scoring: selected source versus direct evidence

The three saved `ac50f134`, `fa51cb9`, and `5ec2565` development traces selected `official_doc:85101` and `official_doc:85107` for `rfp-tech-score-table`. Neither scoped scoring anchor (`11232658` or `11128791`) appeared in the bounded candidate traces. The aggregate `survived` result therefore cannot establish that a scoring source was selected.

Read-only local probe `9a9ce3` at `30ec2cf` compared the four active source rows under captured routing, using the unchanged evidence judge and two independently worded questions. For a blanket obligation to attach a scoring table, both pairs supplied zero direct grounds. For which items belong in an RFP, the selected pair supplied two direct grounds and the scoped scoring pair supplied zero. This isolates a scope distinction, not a demonstrated product false-positive on the original question. It is neither a new rank measurement nor an answer-quality PASS.

### Historical request-error boundary

The retained app-dev log for the `5ec2565` cohort contains two matching failures at 14:30:56.514 and 14:31:01.946 KST. The client artifact masks both as HTTP 500; the two server stacks identify `QueryTimeoutException` backed by `SQLTimeoutException` in the same `LawChunkMapper` query. Resolving the stack against the historical SHA, not current line numbers, identifies generic `findSemanticChunksByText` on the law branch (`7abf46`), not the RAG document query or a verifier rejection. The historical catch only retained collected reviewed evidence when the independently direct condition had already been established. Current scoped review routing and its timeout-preservation test passed in the later full suite, but that regression evidence does not fill the two original rows with invented ranks or prove current HTTP success. No failed request was sent again.

### Current local lexical isolation

Read-only local component diagnostic `a216df` and terminal session 34483 on product `4e4aff1`: all 24 frozen development IDs resolved once, 23 returned SQL candidates and a local judge result, one raised `QueryTimeoutException` (`security-review-procedure`). No evaluation HTTP, query embedding, vector or BM25 fusion, answer generation, oracle, or release gate was run. In particular, the brief process question is not any of the existing omission/submission/post-notice scoped intents; the specialized finder requires exactly one of those intents, so its behavior on the earlier independently worded questions does not establish coverage for the brief process question.

Independent anchors returned but not selected by the isolated judge include `177615` (security omission) and `116900` (CCTV guide). The exact anchors for pre-consultation target/timing (`87028`/`87020`), scoped RFP scoring, public database standard (`11168239`), IRM (`164487`), autonomous consultation, and the AI-law date (`11058178`) were not observed in the isolated SQL candidate lists. These are lexical-path observations, not all-path losses or evidence of absent corpus rows. The saved online checkpoint is not overwritten.

Separate read-only contrast `7af867/35c8a2/30ee4b`: a synthetic end-to-end process question fetched the pinned guide's referral/self-review (177613), submission (177480), and action after receipt (177616), but the unchanged judge found no direct ground either individually or as a group. A bounded sender-side notification wording search returned zero rows, which is not exhaustive. [Incheon's Smart Village program guide](https://ebook.incheon.go.kr/e-book/access/ecatalogt.jsp?Dir=796&callmode=normal&catimage=&eclang=ko&start=48&um=s) explicitly describes notification by the metropolitan review authority in that program; this is not a universal source and was not ingested. A new routing hint alone would not justify suppressing the law timeout under the existing direct-ground guard.

Independent CCTV intent contrast `b88462`: using the same four active source rows and unchanged judge, the purpose/scope question recognized 116900 as direct both alone and in the group, while the implementation question did not, and instead retained operative rows 116912 and 116932. The purpose/scope anchor is not intrinsically disqualified; it should not be force-selected across procedure questions. This does not establish that the original compact guide question is classified correctly online or that the group order is ideal.
