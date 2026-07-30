# Document Discovery Routing Design

## Problem

Short noun-phrase searches such as `CCTV 관련 법령` are document discovery
requests, not requests for a legal conclusion. The current pipeline retrieves
documents but sends the query through proposition-level answer generation and
verification. That path can correctly reject unsupported conclusions while
still returning an irrelevant clarification message.

Entity-level direct-evidence requirements also bias broad discovery toward
operational administrative rules. For CCTV, the inherited installation,
retention, and notice requirements can outrank the law source type explicitly
requested by the user.

## Confirmed runtime evidence

- The request retrieved eight grounds.
- Answer repair stopped with `NO_ALIGNED_SUPPORTED_ATOM`.
- The selected aligned supported atom count was zero.
- The request was not classified as an existing document identity lookup
  because it ended in `법령` without a lookup suffix.

## Design

### Intent

Add a `documentDiscoveryQuestion()` classification for short topic-plus-source
noun phrases, including:

- `○○ 관련 법령`
- `○○ 관련 규정 찾아줘`
- `○○ 가이드/자료/문서 알려줘`

Do not classify substantive legal questions such as `CCTV 관련 법령상 설치
조건은?` or content searches inside a named document as discovery.

Discovery keeps entity aliases, focused search terms, synonym expansion, and
preferred targets for recall. It does not turn entity direct-evidence groups or
section types into mandatory answer propositions.

### Retrieval priority

For discovery requests, add a bounded source-type preference:

- `법령/법률/시행령/시행규칙`: law, then administrative rule, then guides.
- `행정규칙/규정`: administrative rule, then law, then guides.
- `가이드/안내서/매뉴얼/자료/문서`: official and internal documents before
  law and administrative rules.

The preference supplements semantic relevance; it does not fabricate a source
or discard all lower-priority source types. Final UI grounds use the same
source preference and are renumbered after sorting.

### Answer composition

When selected grounds exist, compose a deterministic discovery response from
their titles, source types, agencies, and ground numbers. Deduplicate multiple
chunks from the same document. Do not ask the answer model to generate a legal
conclusion and do not run proposition repair on this metadata-only response.

The response ends with a neutral request to narrow the issue by applicability,
requirements, procedure, deadline, or exception. It does not assert a legal
rule.

### Safety

- Empty grounds still fail closed.
- Substantive questions continue through the existing answer generator,
  verifier, and repair path.
- Discovery output contains only fields copied from selected grounds.
- No CCTV-specific answer is hard-coded. Existing CCTV aliases continue to
  expand the search to `고정형 영상정보처리기기`.
- No changes are made to the 18080 batch runner.

## Verification

1. Intent classification positive and negative tests.
2. Source-priority and ground-renumbering tests.
3. Deterministic composer deduplication and metadata tests.
4. Non-streaming and streaming service tests proving the answer model and
   proposition verifier are not called for discovery.
5. Focused Maven tests, self-review, full Maven test, package build.
6. Restart only 8080 and reproduce `CCTV 관련 법령`.
