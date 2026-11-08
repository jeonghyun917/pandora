# Law text search: rank before hydrating candidate bodies

The current 24-case development search produced 14 responses and 10 HTTP 500s. Nine server stacks terminate in the broad law text mapper at its three-second MariaDB limit. A separate RAG indexed prefix query accounts for the tenth. The frozen failed evaluation was not replayed.

The law mapper now limits a narrow ranked projection before hydrating result bodies and evaluating section/parent projections. The LIKE filters, activation and effective-status filters, score weights, target precedence and three-second timeout remain. Chunk ID breaks previously unspecified ties; no dataset, source, verifier or authority changes.

## Verification

- Mapper structure regression: previous SQL RED, candidate GREEN. Approved-host stable full Maven suite exit 0, session 99333. No isolated DB result inferred from that suite.
- Dedicated MariaDB loopback 23306, fresh isolated datadir, 200,000 synthetic chunks across 10,000 law/admin-rule documents. Java probe loaded old mapper from the pre-change HEAD and candidate mapper from the worktree, bound the same Korean keywords via MyBatis, and compared all 30 projected rows in order across six target/effective-status configurations. All six sequences matched. Old three-second timeout independently reproduced (3023 ms). Old comparison used a 15-second diagnostic limit only inside the isolated fixture.

| Target | Include future | Old (ms) | Candidate (ms, 3s limit) |
|---|---|---:|---:|
| Law | No | 8898 | 1693 |
| Law | Yes | 9556 | 1782 |
| Administrative rule | No | 4723 | 1121 |
| Administrative rule | Yes | 4903 | 1116 |
| Both | No | 13054 | 2170 |
| Both | Yes | 13926 | 2352 |

Probe: plan workspace LawTextSqlProbe.java, session 22685 exit 0. Only the owned temporary DB was shut down. Synthetic latency is not current-corpus performance, and does not resolve the RAG timeout or prove release gates.
