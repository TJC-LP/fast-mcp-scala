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
(`mcp/sut/shared`); only the launcher differs. Antithesis instruments the project's own code on
all three nodes, so coverage guides its search on each: the project's jars on the JVM (third-party
jars are not instrumented), and the Scala.js bundle on Bun. Antithesis documents its JavaScript
instrumentation for Node only; it works on Bun when the image holds a CommonJS bundle, its source
map, a `package.json` and a `node_modules` directory (`mcp/sut/Dockerfile.bun` lists why each is
needed). Check "Software was instrumented" in a run's report: it must list the module
`fast-mcp-scala-sut-bun` next to the Java one.
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
| `eviction_order` | opens an older and a younger session, uses the younger, pushes a drawn number of new sessions, then looks at both (rare) |
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
- A live session is lost only when its node restarted, the driver deleted it, or the node's
  session store was at its cap; a deleted session answers 404; an initialize at the cap is
  admitted or refused with 503. Once a flood has filled a node's store, the driver cannot tell
  which session the cap evicts next (under faults it does not know which of its requests arrived,
  or when), so it only judges sessions on nodes that have not been flooded since they booted.
  The details of a counterexample carry the session id; the server logs `evicted session <id>`
  for every cap eviction.
- At the session cap a session is evicted only after every session that has been idle longer.
  The `eviction_order` rule checks this on a pair of its own sessions, so it holds whatever else
  is in the store: the younger of the two may be gone only if the older is gone too. This is the
  check for the eviction order itself, and the one that covers the session clock.
- A completed task returns the result of its own call; a terminal task status never changes; a
  task handle stays valid while its node keeps running.
- Once faults stop: every node serves again, no handler is still running, a new session can be
  opened, and a dropped GET stream frees its slot.

Assertions inside the server (`mcp/sut`: the Antithesis Java SDK on the JVM, the SDK's fallback
JSON-lines format on Bun) cover what no reply shows: a tool
handler only sees arguments that satisfy its declared input schema, and the branch where a handler
is interrupted before it finishes is reached.

**Regression probes for security findings.** The `probe` rule and two in-server assertions state
hardened behaviour that Claude Security findings showed was missing. They failed on the code
before the fixes (TJC-5087) and pass since; a failure now is a regression.

| Property | What it guards |
|---|---|
| `a request head with bare LF line endings is refused` | The socket backend's request-head parser accepted bare LF terminators |
| `a chunked body with bare LF framing is refused` | The socket backend's chunked-body parser accepted bare LF |
| `a request with conflicting duplicate routing headers is refused` | A repeated `Mcp-Method` / `Mcp-Name` was resolved first-wins |
| `a tools/call argument outside the declared input schema is rejected` | Declared input-schema constraints were not enforced |
| `a tool handler only sees arguments that satisfy its declared input schema` (and the Bun twin) | The same, seen from inside the handler |
| `a dropped GET stream frees its slot` | A GET slot was released only by the response stream's finalizer |

`mcp/known-failing.tsv` lists properties that are expected to fail because their fix has not
landed. It is empty. When a probe is written before its fix, add a line there and remove it with
the fix; any other failing property is a new result to triage.

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
  into `mcp/sut/stage/` (ignored by git) for the images. JVM bytecode and the bundle are the same
  on every platform, so nothing is compiled under emulation. The driver is cross-compiled in its
  image build.
- The Bun image installs Bun's baseline x86-64 build from the checksum-pinned release archive.
  That binary aborts under emulation on an ARM machine, so the local loop builds the image for the
  host's own architecture, and `launch.sh` needs `SKIP_VALIDATE=1` there (`snouty validate` runs
  the x86-64 images locally). Run `local.sh` first in that case.
- `local.sh` writes its evidence (the SDK output of every container, the logs, and `unmet.tsv`)
  to `mcp/out/<timestamp>/`. Sometimes-assertions that need a fault are expected in `unmet.tsv`,
  and so are any lines in `known-failing.tsv`.
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
| `b940de89b2a74b88a3c5a081503bf872-66-21` (2026-11-12, Bun bundle instrumented, `resilient`) | 12 h | The Bun bundle was instrumented: "Software was instrumented" lists `fast-mcp-scala-sut-bun` (37,810 locations, symbols present) next to the Java module, and thread pausing was enabled for the Bun container. 69 properties passed, 7 failed, the same `known-failing.tsv` entries. An earlier attempt (`4ea61d2d86656b0595d50cb1ab8960e8-66-21`) ran healthy but uninstrumented: without a `node_modules` directory the instrumentor skips the bundle and only logs it. |
| `67cab1fe220ec0417fa92b622a92f974-66-21` (2026-11-12, with the TJC-5087 fixes, `resilient`) | 12 h | 76 properties passed, none failed. The seven former `known-failing.tsv` properties pass with no counterexample (bare-LF head 2.2k checks, bare-LF chunked body 2.0k, duplicate routing headers 2.3k, schema rejection 2.3k, schema inside the handler 3.8k on the JVM and 13k on Bun, GET slot 125). This run used the uninstrumented Bun image. |
| `05889e0a7b79dea3751a21c1ce9df0c4-66-21` (2026-11-12, fixes + TJC-5117 + instrumented Bun, `resilient`) | 12 h | 75 properties passed, 1 failed: `a live session is lost only when...`, 6 counterexamples on 292 checks. **A library defect, found by the clock faults.** The legacy session store ordered sessions by wall-clock last-activity time. After the fault injector stepped the clock back about two seconds, a session opened after a flood carried an older timestamp than the flood's sessions, so the next initialize at the cap evicted it as the longest idle one. The counterexample did not carry the session id then, so the eviction is matched by the sequence of events (wall-clock log timestamps running backwards, a cap eviction 0.4 s before the 404, and no other way for a session younger than 31 untouched flood sessions to be the oldest), not by id. Fixed by measuring idle time on the monotonic clock (`Session.monotonicMs`). The same change stops a forward step longer than `sessionIdleTimeout` from expiring every session. The driver's model was also too narrow (a store stays at its cap after a flood) and is now sound. Both modules were instrumented in this run. |
| `229f6a4c7a0938dab93c0d0d770713e7-66-21` (2026-11-12, as above plus the monotonic session clock and the corrected driver model, `resilient`) | 12 h | 76 properties passed, none failed. Both modules instrumented (Java and the Bun bundle), thread pausing on all three nodes. `a live session is lost only when...` held on 422 checks. **This run does not confirm the clock fix**: the corrected driver model stops judging lost sessions on a node whose store has been filled, which is where the defect showed. The `eviction_order` rule was added for that. |
| `a5eb3c52b3e28a8eb79fdb6ef38f8a29-66-21` (2026-11-13, CONTROL: `main` with the session clock fix reverted, plus the `eviction_order` rule, `resilient`) | 12 h | 78 properties passed, 1 failed, as intended: `at the session cap a session is evicted only after every session that has been idle longer`, 17 counterexamples on 1.1k checks. `a live session is lost only when...` passed (598 checks), which shows that property cannot see this defect. Run from a local branch that was never pushed. |
| `32b04b75ffbc3157e8107bedd1241e0c-66-21` (2026-11-13, `main` plus the `eviction_order` rule, `resilient`) | 12 h | 79 properties passed, none failed. The eviction-order property held on 812 checks; its two reachability assertions were met (an older session evicted with the younger kept: 137 times; both evicted: 348 times). With the control above, this is the evidence for the session clock fix. |
