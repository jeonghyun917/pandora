# BM25 title no-match diagnostic evaluation

Date: 2026-09-01 (Asia/Seoul)

## Execution

- Replacement approval SHA-256: `4a5b40911e3c9ef08f94578ef0459db38491281ebc0815614718749076652095`
- Two ordered runs completed `24/24` with request errors `0`.
- OpenAI Embedding API calls: exactly `48`; Answer API calls: `0`.
- Qdrant and MariaDB were read-only. Port `18080` and `output/` were untouched.
- Both runs used the same JAR, configuration, lexical revision, database content fingerprints, and DB/Qdrant parity (`211548/211548` law, `84248/84248` RAG). Qdrant search failures were `0`.

An initial command omitted the required `--case-ids` option and stopped in the local training-selection assertion before runtime evaluation or question transmission. Its log is preserved separately; it consumed no OpenAI call.

## Cause result

Each run produced `22` `BM25_TITLE_NO_MATCH` cases and `2` `APPLIED` cases.

- `20` misses: `TITLE_MISMATCH`
- `0` misses: insufficient planned terms
- `0` misses: no valid hydrated candidate
- `2` misses: `BM25_TITLE_NO_NOVEL_CHUNK`

For the 20 title-mismatch cases, the planner supplied `20` to `47` terms and BM25 inspected `100` candidates in every case. Hydration returned `96` to `100` candidates, but the best title matched only `0` or `1` planned term; no case matched two terms. The primary boundary is therefore the title-term matching/threshold layer, not missing query terms or total hydration failure.

The two no-novel-chunk cases are `project-review-hardware-exclusion` and `msit-tving-investigation`. Expansion applied only to `irm-faithfulness` and `ai-law-enforcement-date` and added no required oracle group.

## Reproducibility findings

The cause classification was identical across runs. One diagnostic counter varied: `pre-consultation-when` hydrated `99` candidates in run 1 and `100` in run 2.

Retrieval metrics also varied in two cases despite identical content fingerprints:

- `security-review-procedure`: matched required groups changed from `1` to `2`.
- `public-data-db-standard`: reranked-through-selected direct hit changed from false to true.

Run 1 control/shadow-fused was `7/24` all-required, `14/24` any-required, `22` groups. Run 2 was `7/24`, `14/24`, `23` groups. Expansion-source remained `0/24`, `0/24`, `0` in both runs.

Each app restart also produced a different stable index revision while content fingerprints and exact counts stayed unchanged. The revision calculator includes the database `updatedWatermark`, so this is provenance metadata variance rather than corpus-content drift.

## Decision

This diagnostic changed neither ranking nor authority. Before testing a title canonicalization or threshold adjustment, make hydration ordering and updated-watermark provenance deterministic; otherwise small retrieval changes cannot be attributed confidently to the candidate change.
