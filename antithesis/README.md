# Antithesis

[Antithesis](https://antithesis.com) runs the system in a deterministic simulation, injects faults
(slow and jammed networks, paused nodes, paused threads, clock jumps) and searches for moments
where a stated property fails. Every failure can be replayed exactly. This directory holds the
workloads that put fast-mcp-scala under it.

The tooling is the `antithesis` agent skill (`workload`, `properties`, `launch`, `triage`, `debug`
modules) and its CLI, `snouty`. Prerequisites: `snouty` 0.9 or later, Podman (or Docker) with
Compose, `jq`, and a JDK for the Mill build. Use Podman on a workstation: the scripts read
`CONTAINER_ENGINE` (default `podman`).

## `mcp`: the HTTP transports

**What it tests.** One server application (`mcp/sut`, the library with seven small tools) on
three HTTP backends, each in its own container: `mcp-netty` (ZIO HTTP), `mcp-socket`
(`JvmSocketHttpBackend`) and `mcp-bun` (Scala.js on `Bun.serve`, which is separate HTTP code from
the JVM backends and what production deployments on Bun run). The tools are shared source
(`mcp/sut/shared`); only the launcher differs. On the JVM the project's own jars are instrumented
by Antithesis and third-party jars are not. The Bun bundle is **not instrumented**: Antithesis's
JavaScript instrumentation assumes Node and `require`, so faults and every driver property apply
to Bun, but coverage does not guide exploration there (ask Antithesis about Bun and ES modules).
Scala Native is not in this workload yet.

**What the driver does.** `mcp/driver` is a Rust program built on hegel, as the skill prescribes.
It speaks MCP over HTTP on both protocol paths, 2026-07-28 and legacy sessions, from several
worker threads at once. Every call carries a fresh token and every tool returns it, so each reply
is judged against exactly one request. Its rules:

| Rule | What it sends |
|---|---|
| `identity` | `echo` or `whoami` on either path, with a per-request `x-test-user` header |
| `progress` | a call that emits 0 to 6 progress notifications before its result |
| `slow` | a silent call of up to 1.6 s, sometimes abandoned by the client |
| `task_start`, `task_poll` | a call that may run as a task; polls and cancels |
| `session_delete`, `get_stream` | ends a session; opens a session's GET stream and drops it |
| `flood` | fills a node's session store past its cap (rare) |
| `malformed` | twelve requests that are each wrong in one way |
| `probe` | regression probes for Claude Security findings (below) |
| `maybe_eventually` | stops faults, waits for recovery, checks what must hold then, ends the timeline |

**Properties it checks.**

- A reply carries its own request's id and its own result; a call that emits nothing is answered
  with exactly one message.
- A handler sees the request headers of its own request (`contextHeaders`), and
  `protocolSessionId` is the session's id on a legacy session and absent otherwise.
- A streamed reply delivers its notifications in order and then its result.
- A well-formed call is answered 200; a malformed request is refused with a 4xx; the server never
  answers 500.
- A live session is lost only when its node restarted or the session cap evicted it; a deleted
  session answers 404; an initialize at the cap is admitted or refused with 503.
- A completed task returns the result of its own call; a terminal task status never changes; a
  task handle stays valid while its node keeps running.
- Once faults stop: every node serves again, no handler is still running, a new session can be
  opened, and a dropped GET stream frees its slot.

Assertions inside the server (`mcp/sut`: the Antithesis Java SDK on the JVM, the SDK's fallback
JSON-lines format on Bun) cover what no reply shows: a tool
handler only sees arguments that satisfy its declared input schema, and the branch where a handler
is interrupted before it finishes is reached.

**Regression probes for security findings.** `mcp/known-failing.tsv` lists the properties that
state a hardened behaviour the code does not have yet, each with the Claude Security finding it
tests. They fail until the finding is fixed and must pass afterwards; any other failing property
is a new result to triage. Remove a line when its fix lands.

**Faults.** The `resilient` profile, with the driver excluded. Each node is a single server with
in-memory sessions and tasks, so it is not meant to survive being killed.

### Build, run locally, launch

```bash
antithesis/mcp/scripts/build.sh            # stage the jars with Mill, build the images
antithesis/mcp/scripts/local.sh 90         # no faults; the eventually fires after 90 s
antithesis/mcp/scripts/launch.sh 12        # 12 compute hours on Antithesis
```

- `build.sh` runs `./mill fast-mcp-scala.antithesisSut.stage` and
  `fast-mcp-scala.antithesisSutJs.stage` on the host and copies the jars and the Bun bundle
  into `mcp/sut/stage/` (ignored by git) for the images. The Bun image installs Bun's baseline
  x86-64 build from the checksum-pinned release archive; the local loop builds it for the host's
  own architecture instead, because the x86-64 binary aborts under emulation on an ARM machine. JVM bytecode is the same on every
  platform, so nothing is compiled under emulation. The driver is cross-compiled in its image
  build.
- `local.sh` writes its evidence (the SDK output of every container, the logs, and `unmet.tsv`)
  to `mcp/out/<timestamp>/`. Sometimes-assertions that need a fault are expected in `unmet.tsv`,
  and so are the lines in `known-failing.tsv`.
- `launch.sh` needs `ANTITHESIS_API_KEY` and `ANTITHESIS_REPOSITORY` in the environment (or a
  `snouty login`) and a registry login for that repository. Check the compute budget before each
  launch; the script only passes the number on.

`DRIVER_EVENTUALLY=off` turns the eventually off; `DRIVER_EVENTUALLY_AFTER_S=<n>` fires it after n
seconds of workload instead of behind its rare draw. `MCP_NODES` (`name=url,...`) names the nodes.

### Runs and findings

| Run | Budget | Result |
|---|---|---|
| `5bb1479871fd547f383eb44e46e222b5-66-21` (2026-11-12, first run, `resilient`) | 12 h | No new library bug. Every correctness property held with no counterexample (never a 500, replies never crossed, header isolation, stream ordering, tasks, recovery). The five `known-failing.tsv` properties failed as listed, including the GET slot (20 counterexamples on 100 checks). One workload bug: `a live session is lost only when...` failed 40 times because the driver did not count a session opened during a flood as evictable by it; fixed in the driver. |
| `2e8900e97819792ab22050657c310b47-66-21` (2026-11-12, Bun node added, `resilient`) | 12 h | No new library bug: 69 properties passed, 7 failed, and all 7 are `known-failing.tsv` entries. The Bun server started on Antithesis's x86-64 hosts (baseline build) and its handlers were exercised (17k interruptions). Reply matching, per-request header isolation and "never a 500" held with no counterexample across the three nodes; the driver picks the node uniformly, so about a third of those checks ran against Bun. The duplicate-routing-header property passes on Bun and fails on the JVM backends (3.0k each way). The earlier driver false failure did not recur. |
