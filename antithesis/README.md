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

**What it tests.** One server application (`mcp/sut`: the library with seven small tools, and
next to them the fixture of the official conformance harness) on three HTTP backends, each in its own container: `mcp-netty` (ZIO HTTP), `mcp-socket`
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
| `maybe_eventually` | stops faults, waits for recovery, checks what must hold then, runs the official conformance harness against one drawn node, ends the timeline |

**Properties it checks.**

- A reply carries its own request's id and its own result; a call that emits nothing is answered
  with exactly one message.
- A handler sees the request headers of its own request (`contextHeaders`), and
  `protocolSessionId` is the session's id on a legacy session and absent otherwise.
- A streamed reply delivers its notifications in order and then its result.
- A well-formed call is answered 200; a malformed request is refused with a 4xx; the server never
  answers 500.
- Every JSON-RPC message a node sends is valid against the specification's JSON Schema for the
  protocol version of the request (below).
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

**Every reply against the specification's JSON Schema.** Each definite HTTP answer the driver
gets, on every rule and while faults are active, is split into its JSON-RPC messages (one for a
JSON body, one per event for a stream), and each message is validated against the
specification's own schema: `schema/2026-07-28/schema.json` for a request sent on the 2026-07-28
path, `schema/2025-11-25/schema.json` for one on a legacy session. The two files are vendored in
`mcp/driver/schema/`; `SOURCE.md` there names the commit of the specification repository they
come from and their checksums. They are compiled into the driver.

Which definition a message is held to follows the message, the way the harness's own
`wire-schema-valid` check picks it (`mcp/driver/src/wire.rs`): a notification by its `method`
(`notifications/progress` gives `ProgressNotification`); a result by the method of its request
(`tools/call` gives `CallToolResult`, `initialize` gives `InitializeResult`) and then the whole
message as a `JSONRPCResultResponse`; an error as a `JSONRPCErrorResponse`, or as the definition
for its code where the schema has one. Formats such as `uri` are checked.

| Property | What it says |
|---|---|
| `a JSON-RPC message a node sends is valid against the specification's JSON Schema for its protocol version` | The message has no schema violation. The details carry the node, the version, the request's method, the violations and the message. |

One deviation is accepted. A request whose id the server cannot read (a body that is not JSON, a
refusal before the body is looked at: wrong `Content-Type`, unknown session, missing session
header) is answered with `"id": null`. JSON-RPC 2.0 requires exactly that, and the official
TypeScript SDK sends the same. The specification's schema does not allow it:
`JSONRPCErrorResponse.id` is a string or an integer and may be left out, and null is neither. The
library keeps the null (decided 2026-11-13), so such a message is checked against the schema
without its `id`.

What this check does not cover:

- HTTP status codes and headers, and whether a result is the right answer. Other properties
  judge those.
- Results of the tasks extension. Under 2026-07-28 the core schema has no definition for a task
  handle (`resultType: "task"`) or for `tasks/get` and `tasks/cancel`, so those are only held to
  the JSON-RPC envelope and the fields every result has.
- Answers with no HTTP status (a dropped connection, a client timeout, a stream cut before its
  end) and bodies that are not JSON. They carry no message.
- The requests the driver itself sends, and the raw-socket probes of the `probe` rule, which
  only read the status line.

**The official conformance harness, once faults have stopped.** The last thing the eventually
does is run `@modelcontextprotocol/conformance` against one node, so the server that is checked
for conformance is the one that went through the faults, with whatever sessions, tasks and
streams they left behind. The node is drawn, and so is the mode. The three modes are the ones
`scripts/conformance.sh` has and CI gates on:

| Mode | Harness arguments | Scenarios |
|---|---|---|
| active suite | `--suite active --expected-failures <baseline>` | 31 (73 checks), all at the 2025-11-25 wire through legacy sessions |
| 2026-07-28 requirements | `--requirements 2026-07-28` | the 37 that revision requires, at its own wire; 13 more run without being scored |
| skills extension scenarios | `--scenario <name>`, one harness process for each | the three of the Skills extension (SEP-2640): `sep-2640-skills-enumeration` (32 checks), `sep-2640-skills-manifest` (6) and `sep-2640-skills-directory` (7), at the 2026-07-28 wire |

The skills scenarios are tagged `[extension]` in the harness. Neither of the other two modes
includes them, and the harness has no option that selects all three at once, so the driver starts
it three times in a row. The three processes share one result directory, one `harness.log` and
one 300 s deadline.

The harness is not re-implemented. The driver starts the pinned binary, waits for it, and reads
the `checks.json` it writes for each scenario (`mcp/driver/src/conformance.rs`). The harness never
runs while faults are active: it reports a dropped connection as a failed check. The driver
container is excluded from faults, so the harness's own process is not disturbed either.

The server under test mounts the harness's fixture (`ConformanceServer.register`: 23 tools named
`test_*`, five prompts, three resources, a resource template, a completion handler, and the
skills of the `SkillsServer` example: three skills, one of them unlisted, and a dynamic provider)
next to the workload's own
tools, on the same port, on all three nodes. No name is shared. The workload's settings needed
one addition, `resourcesSubscribe = true`: the library registers `resources/subscribe` and
`resources/unsubscribe` only then, and the active suite has a scenario for each. The session cap
of 32, the 500 ms keep-alive, `contextHeaders` and enabled tasks do not get in a scenario's way.

One scenario, `dns-rebinding-protection`, only accepts a loopback URL: it checks that a server
reached as `127.0.0.1` refuses a foreign `Host` and accepts its own, and fails outright when
given `http://mcp-netty:8000`. So the driver gives the harness `http://127.0.0.1:<port>/mcp` and
relays the bytes to the node, unchanged. The server then sees `Host: 127.0.0.1:<port>`, a name
the workload's `allowedHosts` already lists, and refuses `evil.example.com` as the scenario
expects. No setting was changed for this.

How the harness's results become properties:

| Property | Type | What it says |
|---|---|---|
| `the server passes the conformance harness's active suite once faults stop`, `the server passes the conformance harness's 2026-07-28 requirements once faults stop`, `the server passes the conformance harness's skills extension scenarios once faults stop` | always | The harness exited 0. This is exactly what `scripts/conformance.sh` gates on. For the skills scenarios the property also fails on a warning or a skipped check, as the script's `skills` mode does. It also fails when the harness cannot start or does not finish within 300 s. The details carry the node, the failed scenarios and checks, and the end of the harness's output. |
| `conformance (active suite): <scenario> passes`, `conformance (2026-07-28 requirements): <scenario> passes`, `conformance (skills extension scenarios): <scenario> passes` | always, or unreachable | One per scenario (31, 37 and 3), so a report names the scenario that failed. The details carry the node, the ids of the failed checks and the harness's own error text for each. The three of the skills mode are `conformance (skills extension scenarios): sep-2640-skills-enumeration passes`, `... sep-2640-skills-manifest passes` and `... sep-2640-skills-directory passes`. |
| `the conformance harness passes the <suite> on <node>` | sometimes | One per suite and node (nine). Shows that the harness really ran, and passed, against each node in a test run. For the skills mode: `the conformance harness passes the skills extension scenarios on netty`, `... on socket`, `... on bun`. |
| `the driver's reading of the conformance results agrees with the harness's exit code` | always | The per-scenario properties are computed by the driver from the result files. This one fails if they and the exit code ever tell different stories, for example after a harness bump that changes its rules. |

The messages of the per-scenario and per-node properties are only known when the driver runs.
The SDK's assertion macros take a literal, because they register it in the assertion catalog at
build time, so these properties go through the SDK's `assert_raw` and the driver writes their
catalog entries itself. The nine per-node properties are declared at startup, so one that is
never met in a whole test run fails. A per-scenario property is declared when it is first
evaluated and passes when never reached: which scenarios exist is only known once the harness
has run, and the per-node properties already fail if it never does.

Which failures count is decided the way the harness decides its exit code, so the properties
and `scripts/conformance.sh` agree:

- Active suite. A check fails when its status is `FAILURE` or `WARNING`. The baseline of
  `conformance/` for the node's platform is passed to the harness (`baseline-jvm.yml` for the two
  JVM nodes, `baseline-js.yml` for Bun). A scenario the baseline lists is excused whole, and a
  `scenario:check-id` entry excuses that one check; neither fails a property. An entry that now
  passes is stale: the harness exits 1 for it, so the first property fails and its details name
  the entry. Both baselines are empty today.
- 2026-07-28 requirements. A scenario fails when the harness's requirement set scores it and one
  of its checks has status `FAILURE`. The set also runs scenarios it does not score: the tasks
  extension's ten and three the harness marks pending. Those get no property and can fail
  nothing. The ones that fail are listed in the details (`not_scored_failing`) and in the
  driver's log.
- Skills extension scenarios. No baseline. A check fails when its status is `FAILURE`, `WARNING`
  or `SKIPPED`, which is the rule of the script's `skills` mode. The harness's exit code for one
  scenario only counts `FAILURE`, so here the first property is stricter than the exit code. A
  skipped check counts because the scenarios skip their checks when the server does not declare
  the extension or `directoryRead: true`; the fixture declares both, so a skip means a scenario
  stopped testing something. A scenario with no result file fails too. The property that compares
  the driver's reading with the exit code compares the `FAILURE` checks only for this mode.

Unit tests in the driver cover these rules (`cargo test` in `mcp/driver`).

Time. In the local loop on an ARM workstation, where the driver's x86-64 Node runs under
emulation, the active suite took 6 to 11 s against a node and the requirement set 7 to 12 s.
Without emulation (the host's own Node against the same server started on the host) the active
suite took 1 to 2 s and the requirement set 3 s. A timeline runs one suite against one node.

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
landed. It is empty. When a probe is written before its fix, add a line there and
remove it with the fix; any other failing property is a new result to triage.

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
- The driver image holds the conformance harness and Node. `build.sh` copies the repository's
  `conformance/package.json`, `conformance/bun.lock` and the two baselines into
  `mcp/driver/stage/` (ignored by git), and the image build installs the harness from them with a
  frozen Bun install, as `scripts/conformance.sh` does. The lock decides every version; there is
  no `npm install` and no `npx`. Bun and Node are release archives pinned by version and
  checksum. To bump the harness, follow `scripts/conformance.sh` (edit `conformance/package.json`,
  regenerate and review `conformance/bun.lock`), then build again; nothing in `antithesis/` names
  the version. The image build also writes `/opt/conformance/manifest.json`
  (`mcp/driver/conformance-manifest.mjs`): the harness's requirement set and the baselines as
  JSON, which is how the driver knows what is scored and what is excused.
- The Bun image installs Bun's baseline x86-64 build from the checksum-pinned release archive.
  That binary aborts under emulation on an ARM machine, so the local loop builds the image for the
  host's own architecture, and `launch.sh` needs `SKIP_VALIDATE=1` there (`snouty validate` runs
  the x86-64 images locally). Run `local.sh` first in that case.
- `local.sh` writes its evidence (the SDK output of every container, the logs, and `unmet.tsv`)
  to `mcp/out/<timestamp>/`. Sometimes-assertions that need a fault are expected in `unmet.tsv`,
  and so are any lines in `known-failing.tsv`.
- In the local loop the harness runs all three suites against all three nodes, and `local.sh` prints
  one line per run with its exit code, counts and duration. The harness's output and result files
  land in `mcp/out/<timestamp>/conformance/<suite>-<node>/`. It also prints how many messages
  were checked against the specification's schema.
- `launch.sh` needs `ANTITHESIS_API_KEY` and `ANTITHESIS_REPOSITORY` in the environment (or a
  `snouty login`) and a registry login for that repository. Check the compute budget before each
  launch; the script only passes the number on.

`DRIVER_EVENTUALLY=off` turns the eventually off; `DRIVER_EVENTUALLY_AFTER_S=<n>` fires it after n
seconds of workload instead of behind its rare draw. `MCP_NODES` (`name=url,...`) names the nodes.
`DRIVER_CONFORMANCE_NODE` (a node's name, or `all`) and `DRIVER_CONFORMANCE_SUITE` (`active`,
`2026`, `skills`, `all`, or `off` for no harness at all) replace the draw of the node and of the
suite. Neither is set on Antithesis. `local.sh` sets both to `all`; give it either as an empty
string to get the draw back. `local.sh` waits for the driver as long as the plan needs: the
workload time, 300 s for the eventually's own checks, and 300 s for each planned harness run
(nine with both set to `all`).

### What the conformance properties do not cover

- The harness runs after faults, never during them. It shows that a server still conforms after
  what the faults did to it, not that it conforms while a fault is active.
- One suite against one node per timeline. Coverage of all nine combinations comes from many
  timelines; the nine sometimes-properties show whether a test run got there.
- The scenarios the 2026-07-28 requirement set does not score (the tasks extension, three pending
  ones) are run and logged, and assert nothing. Eleven of them fail against this server. The
  requirement set says of all thirteen that the harness's own reference fixture cannot pass them
  yet.
- The harness reaches the node through a relay inside the driver, on loopback. The bytes are the
  same, but the server always sees the `Host` `127.0.0.1:<port>`, never the node's own name.
- The Bun node is checked against `baseline-js.yml` and the JVM nodes against `baseline-jvm.yml`
  because the driver knows the node named `bun`. A node with another name gets the JVM baseline.

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
| `138b4b66187ffc9deaf28072fff5b5d9-66-21` (2026-11-13, first run with the conformance and schema properties, `resilient`) | 12 h | 155 properties passed, 2 failed. All 68 per-scenario conformance properties passed, and the harness passed on all three nodes in both suites once faults had stopped. Failed: the null-id property (since removed, see the schema section), and `a streamed reply delivers its notifications in order and then its result`, 18 counterexamples on 1.7k checks. **A library defect, found by a node pause.** A streamed reply that no backend has started within five seconds is released. The node was paused for nine seconds just after a reply was built, so the deadline had passed when it ran again and the backend streamed an empty 200. Fixed by counting the grace in ticks (reproduced in `StreamableHttpHandlerTest` with a clock whose first sleep returns at once). |
