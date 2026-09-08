# Spec coverage

← [README](../README.md) · see also [2026-07-28 upgrade guide](./2026-07-28-upgrade.md) · [CHANGELOG](../CHANGELOG.md)

## Protocol targets

The native path targets **MCP 2026-07-28** and retains an initialization-based compatibility
adapter for the older revisions listed in `Protocol.LegacyProtocolVersions`
([`Protocol.scala`](../fast-mcp-scala/shared/src/com/tjclp/fastmcp/core/Protocol.scala)):
2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05, and 2024-10-07. `server/discover` advertises all
of them, newest first.

## Coverage matrix (MCP 2026-07-28)

| Capability | Status |
|---|---|
| Tools (list, call) + Tool Annotations/hints | ✅ |
| Structured tool output (`outputSchema` + `structuredContent` via `.withOutputSchema`) | ✅ |
| Static resources & resource templates | ✅ |
| Prompts with arguments | ✅ |
| Stateless per-request metadata + `server/discover` | ✅ |
| Required `resultType` + cache hints | ✅ |
| `McpContext` (client info, capabilities, progress, trace metadata) | ✅ |
| Stdio transport | ✅ |
| Streamable HTTP (stateless POST + request-scoped SSE) | ✅ |
| Legacy initialize/session/GET/DELETE HTTP adapter | ✅ |
| `Mcp-Method`, `Mcp-Name`, and `x-mcp-header` validation | ✅ |
| Progress notifications | ✅ |
| MRTR for Roots, Sampling, and Elicitation | ✅ |
| Completion (`completion/complete`) | ✅ |
| `subscriptions/listen` handshake and stream lifecycle | ✅; no dynamic change publishers yet |
| Per-request log level | ✅ (opt-in) |
| Deprecated Roots, Sampling, Logging legacy surfaces | ✅ (compatibility only) |
| Cancellation (`notifications/cancelled`) | ✅ |
| Tasks extension | ✅ (opt-in; no task `input_required` production yet) |
| DNS-rebinding / browser-origin protection (`allowedHosts`, `allowedOrigins`) | ✅ (opt-in) |
| Input limits (`limits: LimitSettings`) + HTTP body / session caps (`maxRequestBodyBytes`, `maxSessions`) | ✅ (on by default) |
| Legacy session idle eviction + SSE keepalives | ✅ |

The deliberately unimplemented pieces (no dynamic subscription publishers, no `input_required`
task suspension, no task-status notifications, no authorization server) are enumerated in the
upgrade guide's [Deliberate boundaries](./2026-07-28-upgrade.md#deliberate-boundaries).

## How coverage is verified

- **Official MCP conformance suite.** [`scripts/conformance.sh`](../scripts/conformance.sh) boots
  the cross-platform `ConformanceServer` over streamable HTTP and drives it with
  `@modelcontextprotocol/conformance`. The `active` mode runs the harness's active suite — 31
  scenarios, 73 checks, every one at the 2025-11-25 wire — against the per-platform
  expected-failure baseline; the `2026` mode runs `--requirements 2026-07-28`, exactly the
  scenarios that revision requires (37 scored, 118 checks; the harness reports but never scores
  extension and pending scenarios, so the `tasks-*` extension scenarios move no gate in either
  mode), and is the only run that sends 2026-07-28 requests. Baselines live in
  [`conformance/`](../conformance/) and are kept **empty** on every platform, so any regression
  fails the gate. [`conformance.yml`](../.github/workflows/conformance.yml) runs both modes on
  every PR for the JVM and Bun — **73/73** active checks and **37/37** scored 2026-07-28 scenarios
  on each; [`native.yml`](../.github/workflows/native.yml) runs the same server as a GraalVM native
  image: the active suite against the unchanged JVM baseline, and the 2026-07-28 run as a
  scenario-by-scenario parity diff against the JVM.
- **Harness pin.** The suite version is pinned in [`conformance/package.json`](../conformance/package.json)
  (currently `0.2.0-alpha.11`, the first release carrying the 2026-07-28 scenario set) and frozen
  by the integrity-hashed [`conformance/bun.lock`](../conformance/bun.lock); the script installs it
  with `bun install --frozen-lockfile` (Bun ≥ 1.4) and runs the installed binary, never `bunx`.
  Bumps are deliberate and recorded in the upgrade guide's gate ledger.
- **Real client over stdio.**
  [`ConformanceTest.scala`](../fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/ConformanceTest.scala)
  drives the official TypeScript SDK client against `AnnotatedServer` over stdio.
- **Bun HTTP routing.**
  [`JsServerHttpTest.scala`](../fast-mcp-scala/js/test/src/com/tjclp/fastmcp/conformance/JsServerHttpTest.scala)
  covers Bun HTTP routing, the legacy session lifecycle, and `HostGuard`.
- **Native stdio smoke.** [`scripts/native-smoke.sh`](../scripts/native-smoke.sh) drives a stdio
  binary (GraalVM or Scala Native) through the full handshake with raw JSON-RPC and `jq`.

See the [CHANGELOG](../CHANGELOG.md) for release-by-release changes.
