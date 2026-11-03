# Source correspondence, dated boundaries and official manual evidence

## Delivered bounded change

Product `f6d0748d59253d6f0f41ee59b8981a94785209c5` committed and pushed
(`097564`). Source-version parsing now preserves each top-level supplementary
content entry and its indexed source path. Nested line arrays remain within
their owner. The semantic planner retains that indexed parent instead of
collapsing separate notices with repeated article numbers. No date is inserted,
no historical text is removed, and the legacy parser remains unchanged.

Actual RED `a43026`: two separate dated entries became one section. After parsing
was corrected, `603c9b` still reproduced one chunk parent instead of two. The
parent-path correction produced focused72/f0/e0/skip0 (`6dc0cc`).

The first sandbox full run stalled on Mockito's JVM attach, with native channel
error `could not open (5) pipe ... operation load`; it was interrupted, not
reported as a code PASS/FAIL. Normal approved-host full run `fe57a4` completed:
1983/f0/e0/skip80,55.438s. Conditional DB/external tests were not enabled. The
previous run's DB evidence is not claimed as this run's verification.

An owned isolated DB was started at23306/PID28408 (`0415a8`), but its test password
was unavailable in this execution; one passwordless connection was refused
(`a7b809`). No credential recovery or authentication bypass was attempted. The
owned session was interrupted and absence of23306 confirmed (`840058`). No test
DB result is claimed. No shared DB/Q write or external RAG evaluation occurred.

Self-review confirmed the change is source-version-only for entry splitting;
the parent rule retains the first indexed supplementary owner, including the
existing law `부칙단위` case. SourceTable/legacy parsing, verifier, oracle,
reference-readiness and mutation gates are unchanged.

## New exact cross-format evidence

`SourceCrossFormatDiagnostic.py` is a read-only diagnostic, not a production
receipt. It checks exact caller-supplied byte hashes, bounded archives and XML,
then uses native PDF text/line-table extraction. No OCR, network, model, DB or Q.
Artifact hashes are the same four acquired official bytes in the prior ledger.

`57634b`, retained in `cross-format-annex-mapped-20261103.json`:

- Annex PDF d168187c... and HWPX26b55fa3... both contain934 non-whitespace
  native characters, with no character-count extras/missing. Reading order is
  different, so character inventory is not treated as ordered/semantic equality.
- All7 native condition tracks map to exactly one PDF row with the same ordered
  conditions and score cells: two rows in the first scoring table, one in each
  remaining five. All35 condition/score positions are preserved. This is a
  local native-line correspondence result, not paint-order/clipping/effect proof.
- PDF has1page,1054glyphs,0images,0curves,1rectangle and7 detected tables
  (outer plus6 nested scoring tables). Object counts do not prove visibility.

Companion `cross-format-companion-20261103.json`: PDF8b11b340... has9pages;
its whitespace-normalized native body equals HWPXc90fba6f... in order,
11926characters. No text was dropped to obtain equality. Rendering remains a
separate condition; native text cannot establish PDF paint or legal status.
The last rendered page shows blue changed text, a review-date provision and a
bare commencement clause. That presentation is retained, not erased or promoted
to an authoritative attachment-role certificate.

Actual updated source coverage (`a187a7`) still has33 matching articles and37
matching non-table sections. Each of10 dated supplementary entries is now
independently reported missing from the companion, rather than one merged miss.
Two unmatched native spans remain:15normalized title characters ending
`일부개정안` and17characters of bare supplementary/commencement text. This is
more accurate diagnosis, not a newly invented10-failure quality evaluation.

## Official source and manual acquisition

The public official body endpoint used by the source page was read directly:
`https://www.law.go.kr/LSW/admRulLsInfoR.do?admRulSeq=2100000287088&admRulId=61899&chrClsCd=010201&joTpYn=Y&prmlNo=2026-63&prmlYd=20261019&efYd=20261019`.
It explicitly owns the2026-63/2026-10-19 supplementary heading and commencement
sentence (`e1dcf9`). The same sentence occurs in multiple older notices.
This proves the current source's dated provision, not the date of a bare clause
in a general attachment. The official annex links share the owning annex entry;
general attachments remain declared general attachments, not inferred enactments.

New officialpublisher path, not a repeated failed web search: NIA board direct
search found the2025manual at
[NIA official distribution](https://nia.or.kr/site/nia_kor/ex/bbs/View.do?bcIdx=28719&cbIdx=99835).
Published2025-11-03; linked PDF acquired directly from
`https://www.nia.or.kr/common/board/Download.do?bcIdx=28719&cbIdx=99835&fileNo=1`.
12198615bytes,243pages,SHA256
`546fd9cf4e42a4195b0543321088bf700d55408530c3c338bc236296ce1db034`
(`f5fce8`). Not sent to OpenAI or ingested into operational corpus.

PDF physical173-174/printed171-172, extracted `6c51ca` and rendered/viewed:
development AND maintenance both outsourced uses cumulative maintenance/development
ratio; development OR maintenance in-house uses operating years. The latter
table explicitly maps years to2/4/6/8/10points. Thus the historical manual
provides a source-owned selection predicate, not arbitrary AND/OR of percentage
and years. Method exclusions, cost definitions and year-Y-1 premise remain.

The distribution page explicitly says it implements2025-12/2025-02-03 revision;
the PDF's internal footer still says2024 on relevant pages. Preserve that
version discrepancy. Do not silently certify its applicability to2026-63,
or replace a new source's omitted condition with an older manual's condition.
The older official2023manual was also acquired from NIA25858/file1:
11137893bytes/212pages/hash
`a903368d97a8a44792f65874422dad4ff6febf25db146fff56bf9cce26903f4d`.
It has different historical cost-measurement context, reinforcing version binding.
Third-party/tokenized mirrors were not adopted.

The same newly acquired official PDF was independently searched for 미준수 and
개선권고 (`758ba2`/`fd8ff6`):5physical pages52,54,185,211,233. Pages52/54 concern
noncompliance with the **prior-consultation procedure**, including recommendation
and escalation after nonperformance; pages185/211 concern performance-score-based
system maintenance, and233 concerns mobile-app performance. None of these
matches directly establishes a general law/system noncompliance→improvement
recommendation proposition. Do not conflate procedural nonperformance,
performance-score classification and general legal noncompliance. No source was
ingested or rewritten to make that bridge.

## Deployment

Official staged package `85602b`:exit0,35.692s,JAR
`0eeda7e4f0b322200017283981158f143a4862bd0b2786a0eee73ac7c7dba269`.
Approved-host initial officialstatus `a9afa4` showed8080/6333 absent, Atlas28400
live. Old8080PID13700 was stale, not stopped by this task. Q was normally started
with the approved official `start-qdrant.cmd`:session42261/PID7580, never stopped.
Read-only law211658/rag84248 remainedgreen.

8080 absence rechecked, officialdeploy `0fafae`/session37860KEEPALIVE;
no internal stop/ExecutionPolicyBypass branch was used. AppPID1620,
runtime512e7148-1cbb-4afe-a5a7-2343265984dc, deployed hash above. Root200
(`840058`); Atlas28400 and Q7580 preserved. Mutation3gatesfalse,
product SHA f6d0748, sharedmain3792766unchanged. Schedule remains paused.

Final fresh `09855b`:JAR68555411bytes/config47d394b4ea5aa5261c62f9c7343a7963f10b0bd41efc1637f6b101ae302b5c32;
indexe39713ce571b7502f930a6efe517c93ccc63d3147d04545314085d3e60d95fa5;
lexicalbae6f4c1277dd16e83d8c0cdbedbda7ced1bc4b191b5b6aafdf6e27796539186.
lawDB=Q211658/ragDB=Q84248/qreadytrue/qfail0, automation statusPAUSED.

## Remaining precise gates / next point

No live source refresh or penalty was run. No failed quality artifact was replayed.
The next proof boundary is NOT another blanket approval:

1. Bind the official manual selection predicate to the intended2026source version
   through an authoritative dated publication/continuation statement, retaining
   the manual's exceptions and definitions. Historical explanation alone is not
   that attestation.
2. Establish the general attachment's legal role and its bare commencement
   clause's specific notice, plus reconcile the missing historical dated clauses.
   Filename, link proximity, exact body text or current page date cannot do this
   by themselves.
3. Render-aware PDF/native equivalence and genuine immutable fidelity receipts
   checked by prepare/transition/rollback; local line matching is not that gate.
4. Inherited isolated-Q/readiness and direct generic-penalty applicability remain
   separate. Only actual penalty PASS opens Difficult12→holdout57→full1003.

No current source/data mutation is safe while these required facts are absent.
Existing active versions remain preserved. Do not resume automation, broaden
authority, weaken readiness or claim allblocking0/overall completion.
