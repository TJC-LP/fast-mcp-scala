# Platforms: JVM, Scala.js on Bun, Scala Native

← [README](../README.md) · see also [Transports](./transports.md) · [Architecture](./architecture.md) · [GraalVM native image](./native-image.md)

fast-mcp-scala is a single native MCP implementation. The entire protocol layer (JSON-RPC
envelope, wire types, router, built-in handlers, middleware, the Tasks state machine) lives in
`shared/`; each platform contributes only a transport backend. The
[architecture diagram](./architecture.md#one-native-core-one-platform-seam) shows the seam.
Capabilities are derived from the registered handler map, so a server can never advertise what it
cannot serve. `McpServerApp[T, Self]` is the declarative entry point on every target; typed
contracts compile and mount unchanged on all three.

## Parity matrix

| Capability | JVM | Scala.js (Bun-first) | Scala Native (experimental) |
|---|---|---|---|
| `McpServerApp[T, Self]` sugar trait | ✅ | ✅ | ✅ |
| `@Tool` / `@Resource` / `@Prompt` + `scanAnnotations[T]` | ✅ | ✅ | ✅ |
| Typed contracts (`McpTool`, `McpPrompt`, `McpStaticResource`, `McpTemplateResource`) | ✅ | ✅ | ✅ |
| `ToolSchemaProvider[A]` auto-derivation from `@Param` | ✅ native macro | ✅ native macro | ✅ native macro |
| `ToHandlerEffect[F]` — plain values / ZIO / Either / Try | ✅ | ✅ | ✅ |
| Stdio transport | ✅ | ✅ (Node stdio) | ✅ (LLVM binary) |
| Streamable HTTP, MCP 2026-07-28 (stateless POST + request-scoped SSE) | ✅ (ZIO HTTP) | ✅ (`Bun.serve`) | ✗ by design¹ |
| Legacy HTTP adapter (initialize, `Mcp-Session-Id`, GET stream, DELETE) | ✅ | ✅ (GET answers 405; per-request SSE covers server→client) | ✗ by design¹ |
| Tasks extension (bearer handles) | ✅ | ✅ | ✅ (over stdio) |
| Custom decoders (`given JsonDecoder[T] → McpDecoder[T]`) | ✅ | ✅ (same shared zio-json path) | ✅ same |
| Standalone binary | GraalVM native image (stdio + HTTP) | — | LLVM binary via Scala Native |

¹ zio-http is not published for Scala Native (upstream support is 4.x-milestoned). The platform
provides no `HttpTransportBackend` given, so `McpServerApp[Http]` programs fail to compile: a
compile-time property, not a runtime failure. A socket-based HTTP backend is in progress
([#81](https://github.com/TJC-LP/fast-mcp-scala/issues/81)).

Node and Deno parity for the HTTP listener is a follow-up; only the `Bun.serve(...)` entry point is
Bun-specific today.

## Scala.js on Bun

What the Scala.js target gives you:

- The same native MCP **server runtime** on Bun: stdio (`runStdio`, Node stdin) and modern
  stateless Streamable HTTP (`runHttp`, `Bun.serve`), plus the version-selected legacy session
  adapter.
- Pluggable tool-argument validation through the shared `Validation.scala` seam (permissive by
  default on every platform).
- The shared `McpContext`: client info and capabilities, request and trace metadata, progress and
  logging, and MRTR-backed Roots, Sampling, and Elicitation, identical on JVM and JS.
- Typed contracts derive their input schemas on Scala.js as well, with no schema-library import.

### Quickstart on Bun

```scala 3 raw
//> using scala 3.9.0
//> using platform scala-js
//> using jsVersion 1.22.0
//> using jsModuleKind es
//> using dep com.tjclp::fast-mcp-scala::1.0.0-RC3
//> using options "-experimental"

import com.tjclp.fastmcp.{*, given}

object HelloBun extends McpServerApp[Stdio, HelloBun.type]:
  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
```

Same shape as the JVM: the `McpServerApp` trait picks up the shared `McpServerCoreFactory` given
and builds the one shared `McpServer` over the Bun `TransportBackend`. Note the double `::` before
the version, which selects the platform artifact (`fast-mcp-scala_sjs1_3`), and `-experimental`,
which the annotation macros require.

Package it to an ES module and run it on Bun:

```bash
scala-cli --power package --js --js-version 1.22.0 HelloBun.scala -o hello.mjs
bun run hello.mjs
```

Scala 3.9 emits Scala.js IR 1.22, so the linker must be 1.22 or newer. scala-cli 1.14 applies the
`//> using jsVersion` directive to compilation but not to the `package --js` linker, which is why
the command repeats `--js-version 1.22.0`. Point Claude Desktop or the MCP Inspector at
`bun run hello.mjs` exactly as you would a JVM command.

### In this repository

`./mill fast-mcp-scala.js.fastLinkJS` links the js module to
`out/fast-mcp-scala/js/fastLinkJS.dest/main.js`. That bundle is an ES module that currently
exports only the conformance server's `startConformance` entry point (this is what
`scripts/conformance.sh js` imports); it has no module initializer, so running it directly starts
nothing. Runnable entry points for the examples are on the [roadmap](../ROADMAP.md). Until then,
run the examples on Bun with the scala-cli recipe above. See
[`HelloWorld.scala`](../fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/HelloWorld.scala)
(shared across platforms) and
[`HttpServerJs.scala`](../fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HttpServerJs.scala)
(Bun HTTP with `McpTool.withSchema`) for the reference sources.

## Scala Native (experimental)

The same shared core compiles to a standalone LLVM binary: no JVM, no JS runtime (about 21 MB in a
debug link, single-digit-second link times). The artifact is `fast-mcp-scala_native0.5_3`, first
published with 1.0.0.

```scala 3 raw
//> using scala 3.9.0
//> using platform native
//> using nativeVersion 0.5.12
//> using dep com.tjclp::fast-mcp-scala::1.0.0-RC3
//> using options "-experimental"

import com.tjclp.fastmcp.{*, given}

object HelloNative extends McpServerApp[Stdio, HelloNative.type]:
  @Tool(name = Some("add"), description = Some("Add two numbers"), readOnlyHint = Some(true))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b
```

`scala-cli package HelloNative.scala -o hello-native` produces the binary; clang/LLVM ≥ 17 must be
installed.

In this repository, `./mill fast-mcp-scala.scalaNative.nativeLink` builds the `AnnotatedServer`
demo binary and `scripts/native-smoke.sh <binary>` drives it through the full MCP handshake, the
same script that gates the GraalVM images.

Caveats (experimental):

- stdio only (see the matrix footnote);
- session and task ids come from `/dev/urandom` (Unix only);
- ZIO's signal handlers and shutdown hooks are no-ops on Scala Native, so shutdown is EOF-driven
  (the client closing stdin ends the loop) and SIGINT falls back to the OS default;
- `java.util.regex` is RE2-backed (no lookaheads), which matters only if your resource URI
  templates embed exotic regex.

## GraalVM native image (JVM)

JVM servers also compile to self-contained native binaries with GraalVM `native-image`: stdio
servers with zero hand-written reachability metadata, HTTP servers with a four-flag recipe, both
CI-gated. Recipes, flags, and the metadata audit loop are in [native-image.md](./native-image.md).

## How platform parity is verified

`ci.yml` builds and tests the JVM and Scala.js modules on JDK 17, 21, and 25 and runs the Scala
Native test suite, links the demo binary, and smokes it over stdio. `conformance.yml` runs the
official MCP conformance suite against the JVM and Bun HTTP servers; `native.yml` runs the same
suite against the GraalVM HTTP image and smokes the GraalVM stdio image. Details in
[spec-coverage.md](./spec-coverage.md).
