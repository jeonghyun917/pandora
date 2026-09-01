# Planned-term title matching candidate — terminal training result

Date: 2026-09-01 (Asia/Seoul)

- Exact execution manifest SHA-256: `a60a456c60b9ec542a3f893bcb172f99d357bd693ca2cc4896e7036eb4c38af1`.
- Two independent ordered 24-case runs completed `24/24` with request errors `0` and runtime-end verification true.
- External use was exactly `48` OpenAI Embedding API calls and `0` Answer API calls. Qdrant and MariaDB were read-only; port `18080` remained absent and `output/` was untouched.
- Runtime artifact `7ebac764aed6626cb12df0a8d1ea2ef9be57ea66e3766ae31de361506790c24f`, config, index revision, lexical revision, database fingerprints, and exact DB/Qdrant counts stayed fixed. Qdrant readiness stayed true and search failures stayed `0`.
- Both runs reproduced control `7/24` all-required, `14/24` any-required, and `22` matched groups.
- The candidate changed BM25-title selection from 2 applied cases to 17 applied cases. Its expansion source contained required groups in 5 cases, but every one was already present in control. Shadow fused therefore remained exactly `7/24`, `14/24`, and `22` groups.
- The independent selector returned `NO_DOCUMENT_EXPANSION_IMPROVEMENT`, eligible `false`.
- The candidate title-match relaxation was reverted. No Difficult-12, holdout, answer evaluation, authority activation, or 1,004-case release gate ran because the mandatory training improvement gate failed.
- Deterministic BM25 hydration and stable content-derived index revision fixes remain in the final change set.

Run JSON SHA-256 values:

- run 1: `249749ac67608410e7daa4154d499723efc99e415c96675bba752a26cc005057`
- run 2: `4ca98519570fb01a0ee86162a878e68b117ea8208a4b7d4cfcada0635633cd3b`
