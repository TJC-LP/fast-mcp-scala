# fast-mcp-scala Architecture

A short tour of how the library is put together. For a user-facing overview see the [README](../README.md); this document is for people who want to understand what happens between "I wrote a `@Tool`" and "an MCP client can call it."

## One native core, one platform seam

As of 0.5.0 the entire MCP protocol layer is native Scala 3 in `shared/` — JSON-RPC envelope, wire types, router, built-in handlers, middleware, and the Tasks state machine. There is exactly one server class, `McpServer[R]`, and one platform seam split along transport lines: `TransportBackend` (stdio + `randomId`) and `HttpTransportBackend` (HTTP) — split so stdio-only programs, and their GraalVM native images, never reach the HTTP stack. The JVM supplies `System.in/out` (`JvmTransportBackend`) and ZIO HTTP (`JvmHttpBackend`); Scala.js supplies Node stdio + `Bun.serve` (`JsTransportBackend`, both givens); Scala Native supplies `System.in/out` (`NativeTransportBackend`) and a hand-rolled HTTP/1.1 + SSE server over `java.net.ServerSocket` (`NativeHttpBackend`), which the JVM can also opt into as the netty-free `JvmSocketHttpBackend`. Every streamable-HTTP decision — session store, only-`initialize`-mints, request→SSE, GET push channel, DELETE, keepalive, transport error bodies — lives once in the shared `StreamableHttpHandler` (`server/transport/http/`), which the JVM and socket backends render onto their own request/response types (the Scala.js backend still carries its own rendering — a follow-up). No vendored SDK remains on any platform (the official TS SDK survives only as a test-time conformance client).

```
                   ┌──────────────────────────────────────┐
                   │  user code: @Tool / typed contracts  │
                   └─────────────────┬────────────────────┘
                                     ▼
                   ┌──────────────────────────────────────┐
                   │  McpServer  [shared/]                │
                   │  registration + lifecycle            │
                   └─────────────────┬────────────────────┘
                                     │ ToolManager / PromptManager / ResourceManager
                                     ▼
                   ┌──────────────────────────────────────┐
                   │  McpRouter  [shared/]                │
                   │  ├─ handler map (capability source)  │
                   │  ├─ RequestContext (fiber-local)     │
                   │  ├─ Session (legacy/stdio plumbing)  │
                   │  ├─ middleware (validation / tasks)  │
                   │  └─ built-ins (server/discover,      │
                   │     tools/*, resources/*, prompts/*, │
                   │     subscriptions/listen, completion │
                   │     + legacy compatibility methods)  │
                   │     — registered only when wired)    │
                   └─────────────────┬────────────────────┘
                                     │ TransportBackend / HttpTransportBackend (the platform seam)
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                      ▼
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
    │  stdio (NDJSON) │    │  HTTP stateless │    │ HTTP streamable │
    │  ZIO Stream /   │    │  ZIO HTTP /     │    │ ZIO HTTP /      │
    │  Node stdin     │    │  Bun / sockets  │    │ Bun, sockets+SSE│
    └─────────────────┘    └─────────────────┘    └─────────────────┘
```

**Honest capabilities**: a built-in handler is registered only when its backing content exists (tools registered ⇒ `tools/list` + `tools/call`; a completion provider ⇒ `completion/complete`; `loggingEnabled` ⇒ per-request logging). `server/discover` capabilities are derived from that handler set, while the legacy `initialize` adapter renders its era-appropriate shape. The server structurally cannot advertise what it cannot serve (issue #56 cannot recur).

## Module layout

```
fast-mcp-scala/
├── shared/src/com/tjclp/fastmcp/        # platform-independent (JVM + Scala.js)
│   ├── core/
│   │   ├── Annotations.scala             # @Tool, @Resource, @Prompt, @Param
│   │   ├── Contracts.scala               # McpTool, McpPrompt, McpDecoder, McpEncoder
│   │   ├── Types.scala                   # ToolDefinition, Content, Message, ...
│   │   ├── Protocol.scala                # protocol versions + JSON-RPC error codes
│   │   ├── Tasks.scala                   # 2026 Tasks extension + legacy wire types
│   │   └── wire/                         # 2026-07-28 and compatibility wire shapes
│   ├── jsonrpc/                          # JSON-RPC 2.0 envelope + McpError
│   ├── codec/                            # DefaultDecodeContext + McpDecoders (zio-json)
│   ├── macros/                           # scanAnnotations, @Tool/@Resource/@Prompt processors,
│   │                                     #   JsonSchemaMacro + MacroUtils (schema derivation)
│   ├── runtime/RefResolver.scala         # FunctionN-arity dispatch helper for the macro
│   └── server/
│       ├── McpServer.scala               # THE server class (both platforms)
│       ├── McpContext.scala              # request context incl. server→client requests
│       ├── McpServerSettings.scala
│       ├── manager/                      # Tool/Prompt/Resource/Task managers
│       ├── router/                       # McpRouter, Builtins, Session, middleware
│       └── transport/                    # TransportBackend + HttpTransportBackend seam,
│           │                             #   StdioLoop, MessageLoop, HostGuard
│           └── http/                     # StreamableHttpHandler + HttpRequest/HttpReply/SseFrame:
│                                         #   the streamable-HTTP semantics, rendered by every backend
│
├── jvm-native/src/com/tjclp/fastmcp/    # compiled by BOTH jvm and scalaNative (not Scala.js)
│   └── server/transport/
│       ├── http/SocketHttpServer.scala   # HTTP/1.1 + SSE over java.net.ServerSocket
│       ├── http/Http1.scala              # request-head parser (Locale-free, RE2-free)
│       └── SocketHttpBackend.scala       # HttpTransportBackend = handler + socket server
│
├── jvm/src/com/tjclp/fastmcp/           # JVM-specific
│   ├── server/transport/JvmTransportBackend.scala   # System.in/out (stdio; netty-free)
│   ├── server/transport/JvmHttpBackend.scala          # ZIO HTTP adapter (default HTTP given)
│   ├── server/transport/JvmSocketHttpBackend.scala    # netty-free opt-in (socket server)
│   └── examples/                         # JVM-only examples (HttpServer, TaskManagerServer)
│
├── js/src/com/tjclp/fastmcp/            # Scala.js (Bun-first)
│   ├── facades/{node,runtime}/           # Node process + Bun/Web-platform facades
│   ├── interop/ZioJsPromise.scala        # ZIO ↔ js.Promise bridge
│   ├── server/transport/JsTransportBackend.scala    # Bun.serve + Node stdio
│   └── examples/                         # Bun HTTP example
│
└── native/src/com/tjclp/fastmcp/        # Scala Native (EXPERIMENTAL)
    ├── server/transport/NativeTransportBackend.scala # System.in/out + /dev/urandom ids
    ├── server/transport/NativeHttpBackend.scala      # socket HTTP given (exported)
    └── examples/conformance/ConformanceServerNative.scala
```

Every module's sources are `shared/src/` + its own platform tree, and `jvm`/`scalaNative` additionally compile `jvm-native/src/` (the socket HTTP layer both share and Scala.js cannot use; its tests in `jvm-native/test/src` run on both platforms through the `CrossPlatformTests` trait). No module reaches across into another's tree otherwise. That is an invariant worth preserving: the schema-derivation macros and every platform-pure example live in `shared/`, so `shared/` compiles standalone on all three targets. Mill wires this in `fast-mcp-scala/package.mill` (module definitions live next to the code; the root `build.mill` holds only versions, compiler flags, and shared traits).

The stdio serving lifecycle is shared too: `StdioLoop` owns the session, the single-writer stdout lock, the outbound drainer fiber, and EOF teardown, so the JVM and Scala Native backends contribute only their stdin stream and their `randomId` source. The JS backend drives Node's callback IO directly and does not use it.

## The annotation path at compile time

When you write:

```scala 3 raw
object MyServer:
  @Tool(name = Some("add"))
  def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

val server = McpServer("MyServer")
server.scanAnnotations[MyServer.type]
```

`scanAnnotations` is an inline macro defined in `RegistrationMacro.scala` (in `shared/` — the same expansion runs on both platforms). Expansion happens in roughly four stages:

1. **Discovery** — `MacroUtils` reflects over `T` (the object's type) and collects every method symbol carrying `@Tool`, `@Resource`, or `@Prompt`.

2. **Schema generation** — for `@Tool` methods, `JsonSchemaMacro.schemaForFunctionArgs` walks the parameter list and emits JSON Schema directly as a `zio-json` AST. It supports primitives, `java.time`, case classes, tagged sums, singleton-case Scala 3 enums, `Option`, collections, maps, `McpSchema` overrides, and the unified decoder-plus-schema `McpInputCodec` escape hatch. `@Param` metadata is folded in (descriptions, examples, required flags, custom schema fragments).

3. **Handler generation** — `MapToFunctionMacro` generates a function `(Map[String, Any], Option[McpContext]) => ZIO[Any, Throwable, Any]` that: (a) extracts each parameter from the `Map` by name, (b) decodes it into the expected Scala type through the shared zio-json decoder path (`McpDecoders` over `DefaultDecodeContext` — identical on JVM and JS), (c) optionally passes an `McpContext` if the method signature asks for one, and (d) calls the original method. The method handle is resolved at runtime via `RefResolver` (no runtime reflection in the hot path).

4. **Registration** — for each discovered method, the macro emits a `server.tool(definition, handler, options)` (or `.prompt`, `.resource`) call, inlined at the call site of `scanAnnotations[T]`.

Everything after stage 4 is identical to the typed-contract path.

## The typed-contract path

No runtime reflection. You build `McpTool`/`McpPrompt`/`McpStaticResource`/`McpTemplateResource` values explicitly — `McpTool[In, Out](name = ...) { args => ... }` picks up a given `ToolSchemaProvider[In]` (macro-provided on both platforms) and an `McpEncoder[Out]` (auto-derivable from any `zio-json` `JsonEncoder`). `.withOutputSchema` additionally derives an `outputSchema` from `Out` and makes every call emit conforming `structuredContent`.

The `server.tool(McpTool)` extension method bridges the shared contract to the same internal `ToolManager.addTool` call the annotation path uses — the typed value is encoded at mount time into both renderings (content + structured) while `Out` is still known.

## Native MCP core

The protocol layer that used to be delegated to the wrapped SDKs is now four small shared packages:

- **`jsonrpc/`** — the JSON-RPC 2.0 envelope (`JsonRpcMessage` with structural discrimination: requests, notifications, responses, and an `Invalid` case so envelope violations answer `-32600` with the offender's id) and `McpError`, including the 2026 allocated codes `-32020` through `-32022`. Unknown resources now use JSON-RPC Invalid Params (`-32602`).
- **`core/wire/`** — the 2026-07-28 wire shapes for discovery, cacheable results, MRTR, subscriptions, capabilities, tools, resources, prompts, deprecated client-input features, and the older initialization adapter.
- **`server/router/`** — `McpRouter` (stateless dispatch and compatibility routing), `RequestContext` (fiber-local per-call version, identity, capabilities, log level, trace metadata, and MRTR retry data), `Builtins`, `Session` (transport queues plus legacy connection state), `WireMapping`, `ValidationMiddleware`, and `TaskRouting`.
- **`server/transport/`** — the `TransportBackend` trait (`serveStdio`, `randomId` — session/task ids come from the platform CSPRNG) and the `HttpTransportBackend` trait (`serveHttp`; a separate trait with a conditional `TransportRunner[Http]` given, so stdio-only programs have no reachable path into the HTTP stack), the shared `MessageLoop` (parse → dispatch → reply framing used identically by every transport), and `HostGuard` (DNS-rebinding protection).

Users only see the public methods on `McpServer` (`.tool`, `.prompt`, `.resource`, `.completion`, `.scanAnnotations`, `.runStdio`, `.runHttp`).

## Transports

All transports are thin adapters over the shared `MessageLoop` + router:

| Transport | Entry point | JVM | Scala.js | Scala Native |
|---|---|---|---|---|
| Stdio | `server.runStdio()` | `ZStream` over `System.in`, serialized writer on `System.out` | Node `process.stdin`/`stdout` | `ZStream.fromReader` over `System.in`, same shared `StdioLoop` |
| Streamable HTTP (default) | `server.runHttp()` | ZIO HTTP (or the `JvmSocketHttpBackend` opt-in) | `Bun.serve` | `SocketHttpServer` over `java.net.ServerSocket` |
| Stateless HTTP | `server.runHttp()` with `stateless = true` | ZIO HTTP (or the opt-in) | `Bun.serve` | `SocketHttpServer` |

The socket server (`jvm-native/`) is deliberately minimal: a blocking accept loop and one fiber per connection on ZIO's blocking executor (`attemptBlockingCancelable`, since closing the socket is the only way to unblock a read on Native), gated at 256 concurrent connections; HTTP/1.1 keep-alive with sequential requests; `Content-Length` and chunked request bodies; `Expect: 100-continue`; HTTP/1.0 close-delimited replies; SSE as chunked transfer-encoding with one flushed chunk per event and a reader-side watcher that notices a vanished peer on a quiet stream. Caps: 8 KiB head (431), 16 MiB body (413), 60 s idle. Plaintext only. `FASTMCP_HTTP_DEBUG=1` prints per-connection failures to stderr.

Modern Streamable HTTP is identical on both platforms: every request is an independent POST, carries per-request protocol metadata plus `MCP-Protocol-Version`/`Mcp-Method`/conditional `Mcp-Name` headers, and receives JSON or a request-scoped SSE stream. There is no modern protocol session, GET endpoint, DELETE lifecycle, SSE event ID, replay, or redelivery. Closing a response stream interrupts its dispatch fiber; `subscriptions/listen` uses the same long-lived POST response. Header mismatches are HTTP 400/`-32020`, unsupported versions are HTTP 400/`-32022`, missing required client capabilities are HTTP 400/`-32021`, and unknown request methods are HTTP 404/`-32601`.

The former initialize + session header + GET/DELETE behavior remains behind version dispatch as a compatibility adapter. `stateless`, `sessionIdleTimeout`, and the session portions of `keepAliveInterval` govern this adapter rather than the 2026 protocol.

Modern Tasks use globally visible bearer handles and therefore work across requests on every transport setting. The legacy adapter keeps task IDs session-scoped and retains its old augmentation/list/result methods.

## Error handling

Tool handlers return `ZIO[Any, Throwable, Out]`. Handler failures surface in-band as `CallToolResult(isError = true, ...)` so the model can self-correct; unknown tool names, bad arguments, and envelope violations are JSON-RPC protocol errors (`-32602` / `-32600`). Resource and prompt handlers follow the same pattern through their managers, with not-found conditions mapped to their spec codes via `McpErrorCarrier`.

## Dependency summary

Pinned in `build.mill`:

- **Scala** 3.8.3
- **ZIO** 2.1.20, **zio-json** 0.7.44 (the wire codec on all platforms), **zio-http** 3.4.0 (JVM default HTTP backend only — the Scala Native / opt-in socket backend has no HTTP dependency)
- **Native Scala 3 macros** for JSON Schema derivation, emitted as `zio-json` ASTs
- **mill-bun-plugin** 0.2.1 (Scala.js + Bun integration)
- **WartRemover** 3.5.6 (linting)
- Test-time only: **`@modelcontextprotocol/sdk` 1.29.0** (conformance client), **ScalaTest** 3.2.19

## Further reading

- [`../README.md`](../README.md) — user-facing feature tour
- [`../CHANGELOG.md`](../CHANGELOG.md) — release notes
- [`./native-core-design.md`](./native-core-design.md) — the native-core design record (M0–M9)
- [`../fast-mcp-scala/docs/parity-audit.md`](../fast-mcp-scala/docs/parity-audit.md) — TS SDK parity audit + closure status
- [`../CLAUDE.md`](../CLAUDE.md) — contributor quick reference for the Mill build
