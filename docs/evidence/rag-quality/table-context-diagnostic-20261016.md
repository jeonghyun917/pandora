# RAG table context diagnostic handoff

## Current checkpoint: 2026-10-16

Legitimate approved Git writes are restored. Product `84e63a31` preserves explicit HWPX cell addresses and spans as evidence rows; malformed tables fall back to raw text. Four extraction tests pass; full backend session52872 exited0 with1659 tests, failures0/errors0/skipped19. The product was pushed and officially deployed (session86031, PID14812, runtime9c2c5c7a-94b1-4e97-9ff8-5b17654265fc, JARc69748e9b2914c2d401b65bb6ee3194b4f980585aaa3291e2243605efc63e308). DB/Q counts match211658+84248; Qdrant ready and search failures0.

Actual source diagnostic3f89ae now has18 evidence fragments and one jointly owned institution/schedule row. The literal source row is SUPPORTED and a date-substituted row is INSUFFICIENT. Natural-language schedule paraphrases remain INSUFFICIENT; cross-table context selection still exists. This is not live Answer or penalty PASS. No database replacement or verifier relaxation occurred. Next implementation must resolve typed semantic/header/section ownership without attributing document-end qualifications by proximity. Any corpus replacement needs a bounded per-document candidate, rollback, and parity contract; bulk force reimport is not a preview. The historical permission-blocked notes below are superseded, not current blockers.

2026-10-15 heartbeat 07:26 UTC. Worktree HEAD 8e2eb09c, branch codex/group-balanced-bm25. Goal null. Existing dirty product files were preserved; no commit, push, deployment, DB write, or external evaluation was attempted.

## New evidence

- Synthetic two-table diagnostic 1bee20: common parent title selects both rows; expanded context includes the other table. ClaimEvidenceMatcher returns INSUFFICIENT for both the true timetable claim and the cross-table wrong timetable claim.
- Actual source diagnostic 9d17a2: corrected local HWPX extractor -> chunkV4 -> quality gate -> parent context -> ClaimEvidenceMatcher. Actual other table is in context. Both the correct National Gugak Center 3–4 month claim and the wrong 5–6 month claim return INSUFFICIENT.
- This establishes neither final answer PASS nor an unsupported-answer failure. It establishes a local table answerability gap while the tested wrong transfer fails closed. No oracle or guard was weakened.
- The diagnostic only calls local components. Test fixture service has no live mappers/clients; executors are shut down in finally. Diagnostic Java and class are in the permitted visualization directory, not product code.

## Resume point

### Source structure constraints, heartbeat 07:46 UTC

- Read-only ac7e64 locates both general-audit and specific-audit section labels inside table paragraph/subList structure, while the plan-change qualification is a paragraph directly under the document section, outside the table. Table-row proximity alone cannot prove which schedules the document-level qualification governs.
- Explicit JSON output ed9e11 confirms two table elements with direct row counts1 and15; the latter has4 merged cells. A safe row-aware extraction design must honor cell addresses/spans and repeated section/header rows rather than blindly zipping cells by position. The plain-text source cannot independently reconstruct those spans.
- No additional production code or database mutations, and no duplicate full-suite run. Repository/Git write restriction remains unchanged. Next implementation must begin with row-span/repeated-header/section-qualification fixtures after legitimate write access is restored.

### Exact merged-cell ownership, heartbeat 08:06 UTC

- Read e0dbfc: row0/column0 has colspan6 and contains the general-audit section heading. Row8/column0 also has colspan6 and contains the specific-audit heading. These section labels are real full-width rows, not generic column labels.
- Row13/columns2 and3 each span2 rows. Their values are the agency scope and year-round schedule. The following row inherits those cells structurally; omitting span propagation loses explicit source ownership. Conversely, propagating past the2-row span would manufacture ownership.
- Existing HwpxHtmlPreviewService.renderTable/renderCell already traverses direct tr/tc children and preserves colspan/rowspan for display (read d3225c). It is a useful existing structural reference, not a semantic evidence parser: HTML rendering alone does not bind claims or document-level qualifications, and should not become a new heavyweight dependency or be fed wholesale as proof.
- This completes the bounded source-structure localization for this document. Further semantic implementation is genuinely gated on legitimate repository write access; do not keep rereading the same source or running the same probe to simulate progress.

### Further localization, heartbeat 07:36 UTC

- Diagnostic35647f: the actual matcher index has53 evidence fragments. Exactly one contains the subject 국립국악원, one contains3~4월, and zero contain both. The emitted fragments are individually 국립국악원 and3~4월. This localizes the true-claim refusal to flattened table cells losing a jointly owned evidence unit, not retrieval absence. The tested wrong-date claim remains INSUFFICIENT.
- Original HWPX XML read46adc2 retains a concrete row with six cells:1 | 국립국악원 | 소속 기관(Ⅰ) |3~4월 | 선정사유 | 대면. Header cells name 대상기관 and 감사 일정. Thus explicit source row/column structure exists before the recursive text-node extractor flattens it. Physical table count alone is not a semantic section identifier; the same inspected table contains both general-audit and specific-audit header rows.
- A future fix must preserve real row/cell ownership and repeated-header/section boundaries from XML. Do not join arbitrary neighboring text fragments or use common parent labels as proof. Document-end change qualifications need separate ownership treatment. No production change has been made for table semantics; repository writes remain unavailable.

1. Read repository evidence top first; include this supplemental evidence once repository documentation writes are permitted.
2. Keep existing verified local corruption guard and HWPX section-selection change unchanged. Their full backend result is 1656 tests, failures/errors 0/0, skipped19, session71912. Do not rerun just for transport.
3. For table answerability, first create a generic failing test that distinguishes real row/column ownership, section titles, and document-end qualifications. Do not implement broad concatenation or promote shared parent labels to proof. Keep any new change separate from the two already verified slices.
4. Current environment excludes repository writes and grants only read access to shared .git. Earlier real Git object insertion failed Permission denied. Do not retry writes blindly, alter ACLs, relocate object databases, or deploy uncommitted artifacts. Restore legitimate repository and Git write permission before commit/push/deploy.
5. Existing RAG reimport is mutating, not a preview, even indexNow=false. No bulk force reimport. Data repair needs bounded per-document replacement/rollback and parity checks.

Penalty actual PASS, Difficult12, holdout57, full1003 and whole RAG completion remain outstanding. The automation remains active.
