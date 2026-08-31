# Task 15 BM25 partial-hydration evaluation abort

## Result

- Status: `ABORTED_BEFORE_OPENAI_RECEIPT`
- Run 1 selected 24 cases, completed 0, and recorded 24 identical HTTP 500 errors.
- Run 2 was not started.
- No retrieval policy or authority flag changed.

## Root cause

The app-dev 8080 Java process had been launched inside the Codex filesystem/network
sandbox. Each embedding connection failed locally with
`java.net.SocketException: Permission denied: getsockopt` before an HTTP request
could reach `api.openai.com`. A network-only control test reproduced the denial
inside the sandbox and reached OpenAI with the expected unauthenticated HTTP 401
outside the sandbox.

## Safety disposition

The failed run is preserved and is not overwritten or replayed. The 24 failed
connection attempts consumed zero successful OpenAI Embedding API calls. The
replacement runtime must be fenced under a new immutable approval hash before a
new evaluation is launched.
