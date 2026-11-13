# Benchmarks

`bench/run.sh` measures the throughput and latency of fast-mcp-scala's HTTP backends on one
machine. It exists so that a performance claim in a pull request or in the docs can be reproduced
from a clean checkout, and so that a regression shows up as a number.

```bash
bench/run.sh                         # jvm-netty, jvm-socket and bun; about 15 minutes
bench/run.sh bun                     # one arm
MODES=modern CONC="1 16" bench/run.sh jvm-netty jvm-socket
PAYLOAD=4096 bench/run.sh            # 4 KiB carried by every request and reply
bench/summarize.py after.jsonl before.jsonl   # same tables, with the ratio to a baseline
```

The script's header lists every setting. Results are JSON lines under `bench/results/` (ignored by
git); the summary is printed as Markdown.

## What is measured

- **Server:** `BenchServer` (`com.tjclp.fastmcp.examples.bench`), the same two typed tools on every
  platform. `add` is a few hundred bytes each way and measures per-request overhead. `echo` returns
  its argument, so `PAYLOAD=N` puts N bytes in both the request and the reply.
- **Arms:** `jvm-netty` (ZIO HTTP, the JVM default), `jvm-socket` (`JvmSocketHttpBackend`), `bun`
  (the Scala.js `fullLinkJS` bundle on `Bun.serve`), `scala-native` (the socket backend linked
  release-fast).
- **Protocol paths:** `modern` is MCP 2026-07-28, one self-describing request per POST. `legacy`
  is a 2025-11-25 session: each worker initializes once and then calls the tool. `stateless` is
  2025-11-25 against `stateless = true`, which answers plain JSON.
- **Load:** `bench/mcpbench`, a closed-loop generator written in Go (standard library only). Each
  worker owns one keep-alive connection and sends its next request when the previous reply has been
  read to the end. Every reply is checked for its request id and a result; a reply that fails the
  check is counted as an error and excluded from the latencies. The warm-up doubles as a preflight:
  a server that answers wrongly fails the run before anything is recorded.
- **Per cell:** the median requests per second over `REPEATS` runs, the lowest and highest run,
  and the median p99 latency. Resident memory is read once per server, after its last cell.

## Reading the numbers

These rules are the difference between a measurement and an anecdote. Keep them when you quote a
result.

- **One machine, one configuration.** The load generator runs on the same host as the server and
  competes with it for cores. On a 16-core machine that barely matters at 1 worker and matters a
  lot at 64. Say which machine, which commit and which date; the summary's first line has them.
- **Closed loop.** A worker waits for its reply before it sends again, so a slow server is offered
  less load. Requests per second is therefore the server's capacity at that concurrency, and the
  latencies do not include queueing that an open-loop client would see.
- **Tiny calls measure overhead.** With `add`, almost all the time is the transport and the
  dispatch, which is what this harness is for. A real tool that takes 50 ms will not get faster
  because the transport saved 20 microseconds. Use `PAYLOAD` to see how a lead changes as copying
  and JSON work take over.
- **Bun is single-threaded.** The `bun` arm runs the whole server on one thread, so its numbers at
  16 and 64 workers are a ceiling for one process, not a statement about Bun's HTTP server.
- **Thread-per-connection against an event loop.** The socket backend serves each connection on
  its own thread and admits 256 at once; netty multiplexes. The harness holds at most 64
  connections, which is inside both designs' comfortable range and says nothing about thousands of
  idle clients.
- **JVM arms are warmed.** Ten seconds at 16 workers before the first cell, on whatever JDK is
  first on `PATH` (recorded in the summary). Cold-start cost is not measured here.
- **Quote the row, not the cell.** Report every arm at a worker count together, with the p99, and
  say when a change made one path slower.
- **No CPU pinning, no isolation.** The script refuses to start a group when the load average is
  above `MAX_LOAD` and records the load after each one, but three repeats and the spread are the
  only other protection against a noisy machine. If the spread is wide, rerun before drawing a conclusion.

## Not covered yet

The official Go and Java SDK arms, a GraalVM native-image arm, start-up time, idle-connection
memory and open-loop latency are planned separately (TJC-2224).
