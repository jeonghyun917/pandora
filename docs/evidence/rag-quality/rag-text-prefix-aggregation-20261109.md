# RAG text search: aggregate prefix scores before visibility joins

The initial development search's video/CCTV case failed in the indexed RAG prefix query, distinct from the nine law substring timeouts. Aggregate the same baseline/prepared prefix rows once per chunk before joining document and chunk eligibility. Captured-generation membership, active version selection, quality, document type, score, top-K order and three-second limit are retained.

Mapper regression RED 82261 → GREEN 5c7f89. Approved-host full Maven exit 0, session 85696, plan log full-rag-aggregate-20261109.log.

Dedicated MariaDB fixture, session 12873 exit 0: 84,000 chunks and 504,000 baseline term rows, plus an explicit captured generation and prepared lexical rows. All projected values and top-30 order matched the former mapper for baseline and captured routing. A captured visible member was selected, a nonmember and a high-scoring rejected member were excluded. Candidate 1073.3/761.3 ms with the three-second limit; former mapper 1455.0/3547.3 ms with the diagnostic-only 15-second cap in the fixture. Probe is retained in the plan workspace as RagTextSqlProbe.java; only the owned test DB was shut down.

This does not resolve remaining law substring timeouts or prove current-corpus quality. No verifier, oracle, dataset or authority changes.
