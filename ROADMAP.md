# Roadmap

Concrete work items, roughly in order. Issues are tracked on
[GitHub](https://github.com/TJC-LP/fast-mcp-scala/issues); release history is in
[CHANGELOG.md](CHANGELOG.md).

## 1.0.0 — first stable release

- Native Scala 3 MCP core (no vendored SDK): JSON-RPC, wire types, router, transports.
- **MCP 2026-07-28** as the primary protocol, with an initialization-based compatibility adapter
  for 2025-11-25 and earlier revisions.
- Three platforms from one `shared/` tree: JVM (stdio + HTTP), Scala.js on Bun (stdio + HTTP),
  Scala Native (stdio, experimental).
- GraalVM native images for stdio and HTTP servers with zero hand-written reachability metadata.
- Official MCP conformance suite gating every PR on JVM, Bun, and the GraalVM binary.

## 1.1.0

- **Streamable HTTP on Scala Native** over a `java.net.ServerSocket` backend, and the same server
  as a netty-free opt-in on the JVM ([#81](https://github.com/TJC-LP/fast-mcp-scala/issues/81)).
- **Runnable Scala.js entry points**: give the js module a `mainClass` or exported starters so a
  linked bundle can be run directly with `bun run` (today only the conformance server exports one).
- **Binary-compatibility gate**: MiMa against 1.0.0 so every 1.x release is checked, not promised.
- Test-client bump to the current TypeScript SDK.

## Toward full protocol support

Items required by the [MCP SDK tiering system](https://modelcontextprotocol.io/community/sdk-tiers)
that fast-mcp-scala does not implement yet:

- **An MCP client.** The conformance suite scores a client leg as well as a server leg;
  fast-mcp-scala is server-only today, which caps it at Tier 3 regardless of server conformance.
- **Dynamic subscription publishers** so `subscriptions/listen` streams list and resource change
  events, not only the acknowledgement handshake.
- **Tasks**: `input_required` suspension and task-status notifications.
- A documentation site alongside the Markdown docs in `docs/`.

## Protocol tracking

New MCP specification revisions are implemented within the Tier 2 six-month window, and the
conformance oracle pinned in `scripts/conformance.sh` is bumped deliberately, with the reasoning
recorded in [docs/2026-07-28-upgrade.md](docs/2026-07-28-upgrade.md).

## SDK tiering

fast-mcp-scala aims to be listed as an official MCP SDK, entering at **Tier 3** and moving to
**Tier 2** once the client leg exists. The response targets we hold ourselves to now are the Tier 2
ones: issues triaged within a month, critical (P0) bugs resolved within two weeks. Dependency
handling is described in [DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md).
