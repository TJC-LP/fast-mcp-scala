# Upgrading to 1.0.0 from 0.x

← [README](../README.md) · see also [MCP 2026-07-28 protocol upgrade](./2026-07-28-upgrade.md) · [Custom types](./custom-types.md) · [Transports](./transports.md) · [CHANGELOG](../CHANGELOG.md)

This page is the **source-level** migration guide for projects that depend on a 0.x release
(0.1.1 … 0.4.0) or a 1.0.0 release candidate. It is organised by the compiler error you will
see. The **protocol** side of the release — what changed on the wire between MCP 2025-11-25 and
2026-07-28, the compatibility adapter, the review matrix and the release gate ledgers — lives on
its own page: [MCP 2026-07-28 protocol upgrade](2026-07-28-upgrade.md). The full list of
intentional behaviour changes is in [CHANGELOG.md](../CHANGELOG.md) (`[1.0.0]`, then the
`[1.0.0-RC1]`–`[1.0.0-RC3]` and `[0.5.0]` sections, which were never published on their own).

Every error below was produced by compiling a real downstream server (three of the org's
own consumers plus the README `HelloWorld`) against 1.0.0 with Scala 3.9.0 and, where the
consumer is a Scala.js project, Scala.js 1.22.0. Fix the errors in the order of the sections:
the toolchain floor first, then the import, then symbols — a project on the wrong compiler
produces nothing but noise, and a file with one failed import produces misleading secondary
errors (see [Troubleshooting](#troubleshooting)).

<!-- PENDING MERGE (delete in R3): this page assumes PR #98 (no `-experimental`, TJC-2335) and
     PR #102 (root exports, TJC-2336) are merged before the v1.0.0 tag. If either slips, revise
     §1.4 and §2.2 here and the CHANGELOG `### Upgrading` import bullet before merging PR B. -->

## Checklist

1. Compiler **Scala 3.9.0 or newer** (§1.1).
2. Scala.js projects: **Scala.js 1.22.0+ linker** — Mill: mill-bun 0.3.1 with an explicit
   `scalaJSVersion` on every `BunScalaJSModule` and a committed `bun.lock`; scala-cli:
   `//> using jsVersion 1.22.0` (§1.2, §1.3).
3. WartRemover users: **3.6.1** (§1.5).
4. Remove `-experimental` from `scalacOptions` — 1.0.0 no longer needs it (§1.4).
5. `import com.tjclp.fastmcp.{*, given}` — the `given` selector is mandatory (§2.1).
6. Replace removed symbols: `FastMcpServer`/`JsMcpServer`, `JacksonConverter`, `@ToolParam`,
   `EmbeddedResourceContent`, `facades.server`, `connect(...)`, `sttp.tapir.*` (§3).
7. Re-check `match` expressions over `Content` and `ResourceContents` (§4).
8. Re-check registration code: `server.tool(...)` is an effect; duplicate or non-literal
   annotations are compile errors; template literals are verbatim (§5).
9. Re-check HTTP settings: `127.0.0.1` default bind, `stateless` scope, the Bun `BunHttpHandle`,
   no per-request handler (§6).
10. Tell your clients about **415 / 413 / -32700 / 403** (§7).

## 1. Toolchain floor

### 1.1 Scala 3.9.0 or newer

1.0.0 is compiled with Scala 3.9.0 LTS and emits TASTy 28.9. A Scala 3.8 (or older) compiler
cannot read it. 1.0.0-RC3 (Scala 3.8.3, TASTy 28.8) is the last release a Scala 3.8 project
can consume.

Compiling the README `HelloWorld` with `//> using scala 3.8.3` against 1.0.0 gives 22 errors;
the first two are:

```
error while loading language,
TASTy file scala/language.tasty could not be read, failing with:
  Forward incompatible TASTy file has version 28.9, produced by Scala 3.9.0-bin-nonbootstrapped,
  expected stable TASTy from 28.0 to 28.8.
  To read this TASTy file, use a newer version of this tool compatible with TASTy 28.9.
  Please refer to the documentation for information on TASTy versioning:
  https://docs.scala-lang.org/scala3/reference/language-versions/binary-compatibility.html
error while loading Exports$package,
TASTy file com/tjclp/fastmcp/Exports$package.tasty could not be read, failing with:
  Forward incompatible TASTy file has version 28.9, produced by Scala 3.9.0,
  expected stable TASTy from 28.0 to 28.8.
  To read this TASTy file, use a newer version of this tool compatible with TASTy 28.9.
  Please refer to the documentation for information on TASTy versioning:
  https://docs.scala-lang.org/scala3/reference/language-versions/binary-compatibility.html
[... the remaining scala/*.tasty files, then cascades such as ...]
-- [E006] Not Found Error: Hello.scala:8:26
8 |object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:
  |                          ^^^^^^^^^^^^
  |                          Not found: type McpServerApp
22 errors found
Compilation failed
```

The first refused file is the Scala 3.9.0 standard library that the 1.0.0 POM pulls in
transitively; the library's own `Exports$package.tasty` follows. Every `Not found: type ...`
after that is a cascade, not a missing symbol.

Fix:

```scala
//> using scala 3.9.0                       // scala-cli
def scalaVersion = "3.9.0"                  // Mill
scalaVersion := "3.9.0"                     // sbt
```

A module-local bump works in one direction only: a Scala 3.9.0 module may depend on your
remaining Scala 3.8.3 modules (3.9 reads 3.8 TASTy), but no Scala 3.8.3 module may depend on
1.0.0 or on a module that does.

### 1.2 Scala.js 1.22.0 linker — `compile` passes, `link` fails

Scala 3.9.0 emits Scala.js IR 1.22, and so does `fast-mcp-scala_sjs1_3`. A linker older than
1.22 (scala-cli 1.14's default 1.21.0; Mill 1.1.x's bundled worker, which stops at IR 1.20)
refuses both. **The compiler never reads dependency IR, so `compile` succeeds.** The failure
appears only when you link (`scala-cli --power package --js`, Mill `fastLinkJS` / `bundle`,
sbt `fastLinkJS`):

```
Exception in thread "main" org.scalajs.ir.IRVersionNotSupportedException: Failed to deserialize a file compiled with Scala.js 1.22 (supported up to: 1.21): .../jar/library.jar:/HelloWorld$$anon$1.sjsir
	at org.scalajs.linker.interface.unstable.IRFileImpl$$anonfun$withPathExceptionContext$1.applyOrElse(IRFileImpl.scala:63)
	...
Caused by: org.scalajs.ir.IRVersionNotSupportedException: This version (1.22) of Scala.js IR is not supported. Supported versions are up to 1.21
	at org.scalajs.ir.VersionChecks.checkSupported(ScalaJSVersions.scala:77)
	...
[error]  Error: main.js not found after Scala.js linking (no files found)
```

The file named first is usually **your own** `.sjsir` (it is 1.22 IR too), not the library's.
With a Mill 1.1.5 bundled worker the message reads `(supported up to: 1.20)`.

Fix:

```scala
//> using jsVersion 1.22.0                  // scala-cli (compile and package)
def scalaJSVersion = "1.22.0"               // Mill, on every Scala.js module (see 1.3)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")   // sbt, project/plugins.sbt
```

### 1.3 Mill: mill-bun 0.3.1, `scalaJSVersion` everywhere, frozen `bun.lock`

Mill 1.1.x cannot link IR 1.22 with its bundled worker, so Scala.js consumers on Mill move to
`mill-bun-plugin` 0.3.x (built against Mill 1.1.5; no Mill bump is needed):

```scala
//| mvnDeps:
//| - com.tjclp::mill-bun_mill1:0.3.1
```

Three knock-on effects of the plugin bump, none of them fast-mcp-scala's, all of them hit on
the first build:

- `scalaJSVersion` is **abstract on every `BunScalaJSModule`** — your agent and example modules
  need it too, not only the module that depends on fast-mcp-scala. Modules that stay on
  Scala 3.8.3 can keep the linker they used before (`"1.20.2"` is what Mill 1.1.5's bundled
  worker was).
- Installs are **frozen**. A module with `bunDeps` fails at `bunInstall` / `run` / `bundle`
  until its lockfile is committed:

  ```
  java.lang.RuntimeException: Missing <module>/bun.lock. Run this module's bunLock command and commit the generated lockfile.
  ```

  Run `./mill <module>.bunLock` once per such module and commit the `bun.lock`. A server module
  whose only Scala.js dependency is fast-mcp-scala needs **no** lockfile: the 1.0.0
  `_sjs1_3` jar carries no Bun dependency manifest (the TypeScript SDK is a test-only
  dependency of the library). `MILL_BUN_REQUIRE_LOCKFILE=false` is the staged-migration
  escape hatch.
- `bunBundle` is now an alias of `bundle`, and the artifact is written to
  `out/<module>/bundle.dest/dist/main.js` — a hard-coded `.../bunBundle.dest/dist/main.js`
  path makes Bun fail with `error: Module not found`. Derive the path from the task's
  `PathRef` instead.

### 1.4 `-experimental` is no longer required

The 1.0.0 release candidates were compiled with `-experimental`, which stamped every public
definition `@experimental` and forced the flag onto every consumer; a build without it failed
with

```
object SelfScan is marked @experimental: Added by -experimental

Experimental definition may only be used under experimental mode:
  1. in a definition marked as @experimental, or
  2. an experimental feature is imported at the package level, or
  3. compiling with the -experimental compiler flag.
```

1.0.0 is not compiled with the flag (the annotation macros use only stable reflect API), so
remove `-experimental` from your `scalacOptions`. Keeping it is harmless.

### 1.5 WartRemover 3.6.1

The WartRemover compiler plugin is published per full Scala version. 3.5.6 has no 3.9.0 build:

```
[error] Error downloading org.wartremover:wartremover_3.9.0:3.5.6
[error]   not found: https://repo1.maven.org/maven2/org/wartremover/wartremover_3.9.0/3.5.6/wartremover_3.9.0-3.5.6.pom
```

Use `org.wartremover:::wartremover:3.6.1` (the first release for the 3.9.0 compiler) on the
modules you moved to 3.9.0; `wartremover-contrib` 2.2.0 is a plain `_3` library and needs no
change. fast-mcp-scala's own tree and a strict downstream profile (`-Werror
-Wnonunit-statement -Wvalue-discard -Wsafe-init -new-syntax -language:strictEquality` plus
`-Xcheck-macros`) both compile clean under 3.6.1.

### 1.6 Transitive dependencies you may have leaned on

Tapir, ApiSpec, sttp, Circe, Cats and jawn are no longer production dependencies (they were in
the RC3 POMs). If your code imports any of them for its own purposes, declare the dependency
yourself; if you imported them only for fast-mcp-scala's sake, delete the imports (§3.5).

## 2. The import

### 2.1 `import com.tjclp.fastmcp.{*, given}` — the `given` selector is mandatory

Up to 0.3.x a bare wildcard plus two explicit given imports was the idiom:

```scala
import com.tjclp.fastmcp.*
import com.tjclp.fastmcp.server.McpServer.given
import com.tjclp.fastmcp.server.TransportRunner.given
```

Since 0.5.0 each platform contributes exactly one thing the shared code cannot: the
`TransportBackend` given (and, on JVM and Bun, the `HttpTransportBackend` given). They are
re-exported from the package root as **givens**, and `McpServer.given` — the factory
`McpServerApp` uses — now requires one. A bare `import com.tjclp.fastmcp.*` therefore fails
with exactly one error (identical on JVM and Scala.js):

```
-- [E172] Type Error: ExampleServer.scala:15:68
15 |object ExampleServer extends McpServerApp[Stdio, ExampleServer.type]:
   |                                                                    ^
   |No given instance of type com.tjclp.fastmcp.server.McpServerCoreFactory was found for parameter factory of constructor McpServerApp in trait McpServerApp.
   |I found:
   |
   |    com.tjclp.fastmcp.server.McpServer.given_McpServerCoreFactory(
   |      /* missing */summon[com.tjclp.fastmcp.server.transport.TransportBackend])
   |
   |But no implicit values were found that match type com.tjclp.fastmcp.server.transport.TransportBackend.
   |
   |Note: given instance instance in package com.tjclp.fastmcp was not considered because it was not imported with `import given`.
1 error found
Compilation failed
```

Fix — one line:

```scala
import com.tjclp.fastmcp.{*, given}
```

The two old explicit given imports may stay or go: they still resolve, and Scala 3.9.0's
`-Wunused:all -Werror` does not report them as unused. Deleting them is the recommended end
state. (`import com.tjclp.fastmcp.given` added next to the old lines is the minimal
alternative.)

### 2.2 Names that are not in the root export

Since 1.0.0 the root import covers the whole documented surface — including `TaskSettings`,
`LimitSettings`, `TaskSupport`, `TaskOwnerKey`, `LoggingLevel`, `ProgressToken`, the
`ResourceContents` / `TextResourceContents` / `BlobResourceContents` payload types, and every
shape a handler names to call the `McpContext` / `McpServer` surface
(`CreateMessageRequestParams` / `CreateMessageResult` with `SamplingMessage`, `ModelPreferences`,
`ModelHint`, `ToolChoice`; `ElicitRequestParams` / `ElicitRequestUrlParams` / `ElicitResult`;
`ListRootsResult` / `Root`; `Implementation` / `ClientCapabilities`; the `completion/complete`
types `CompleteRequestParams`, `CompletionReference`, `PromptReference`,
`ResourceTemplateReference`, `CompletionArgument`, `CompletionContext`, `Completion`). The
release candidates needed those imported by name — `com.tjclp.fastmcp.server.{TaskSettings,
LimitSettings}`, `core.{TaskSupport, TaskOwnerKey, LoggingLevel}`,
`core.wire.{TextResourceContents, BlobResourceContents}` — and such imports stay valid next to
the root import, so leave them if you have them.

Anything else lives at its package path: transport and settings internals under
`com.tjclp.fastmcp.server.transport.*` (`HostGuard`, the Bun `BunHttpHandle`), and
`core.wire.Tool` — the sampling `tools` element, deliberately not exported because it would
collide with the `@Tool` annotation (`import com.tjclp.fastmcp.core.wire.Tool`). The symptom is
always `[E006] Not found: <Name>`.

## 3. Renamed and removed symbols

| You have (0.x) | Compiler says | Use in 1.0.0 |
|---|---|---|
| `FastMcpServer("name")` (JVM ≤ 0.4.0) | `value FastMcpServer is not a member of com.tjclp.fastmcp.server` / `Not found: FastMcpServer` | `McpServer("name")` → `McpServer[Any]` |
| `JsMcpServer[R]`, `JsMcpServer.typed[R]`, `case s: JsMcpServer[?]` (JS ≤ 0.4.0) | `value JsMcpServer is not a member of com.tjclp.fastmcp.server - did you mean server.McpServer?` / `Not found: type JsMcpServer` | `McpServer[R]`, `McpServer.typed[R]("name")`, `case s: McpServer[?]` |
| `FastMcpServerSettings` (≤ 0.3.0-rc4 alias) | `Not found: type FastMcpServerSettings` | `McpServerSettings` |
| `given JacksonConverter[T] with def convert(name, rawValue, mapper)` (JVM ≤ 0.4.0) | `value JacksonConverter is not a member of com.tjclp.fastmcp.macros` / `Not found: type JacksonConverter` | `given JsonDecoder[T]` (zio-json; lifted to `McpDecoder[T]` automatically), or `given McpInputCodec[T]` when the JSON shape differs from the case class — see [custom-types.md](custom-types.md). `java.time` fields need an explicit `JsonDecoder` |
| `@ToolParam("...")`, `@ResourceParam`, `@PromptParam` (≤ 0.2.x) | `value ToolParam is not a member of com.tjclp.fastmcp.core` / `Not found: type ToolParam` | `@Param(description, example, required, schema)` — same shape |
| `import sttp.tapir.generic.auto.*` (RC3 and earlier typed contracts), `import sttp.tapir.*`, `derives Schema` | `Not found: sttp` (`- did you mean http?` when `zio.http` is in scope) | Delete the import; schemas derive natively. `given Schema[T]` → `McpInputCodec[T]` / `McpSchema[T]`; per-field tweaks → `@Param(schema = ...)`; whole tool → `McpTool.withSchema` — table in [custom-types.md](custom-types.md#migrating-from-tapir-schema-overrides) |
| `EmbeddedResource(EmbeddedResourceContent(uri, mimeType = "application/pdf", blob = Some(b)))` (≤ 0.4.0) | `Not found: EmbeddedResourceContent` | `EmbeddedResource(BlobResourceContents(uri = u, blob = b, mimeType = Some("application/pdf")))`; text: `EmbeddedResource(TextResourceContents(uri = u, text = json, mimeType = Some("application/json")))`. Both live in `core.wire` and are root-exported since 1.0.0 (the release candidates needed `import com.tjclp.fastmcp.core.wire.{TextResourceContents, BlobResourceContents}`). Note `mimeType` is now `Option[String]`, `text`/`blob` plain `String` |
| `import com.tjclp.fastmcp.facades.server as tsdk`; `tsdk.WebStandardStreamableHttpServerTransport`, `tsdk.WebStreamableHttpOptions.stateless` (JS ≤ 0.4.0) | `value server is not a member of com.tjclp.fastmcp.facades`, then `Not found: tsdk` at every use | Delete — the TypeScript SDK facade left with the native core (0.5.0) |
| `mcpServer.connect(transport)` (JS ≤ 0.4.0) | `value connect is not a member of com.tjclp.fastmcp.server.McpServer[Any]` | `server.runHttp()` (the library owns the listener; `McpServerSettings(stateless = true, httpEndpoint = "/your/path")` selects the stateless legacy adapter and a nested endpoint); on Bun, `startStatelessHttp(): BunHttpHandle` when you need `.stop()` |
| `ErrorCodes.ResourceNotFound`, `ElicitRequestUrlParams.requiredError` (RC1–RC3) | `Not found` / `is not a member` | `ErrorCodes.InvalidParams` (modern) / `LegacyResourceNotFound` (legacy); `McpContext.elicitUrl` with MRTR |
| `HostGuard.isAllowed(host, origin, allowed: Set[String])` (RC3) | `is not a member` (overload gone) | `HostGuard.isAllowed(host, origin, settings: McpServerSettings)` |
| `McpServerSettings(debug = ..., logLevel = ..., warnOnDuplicateResources = ..., warnOnDuplicateTools = ..., warnOnDuplicatePrompts = ..., dependencies = ...)` | named-argument error (the fields are gone; none was ever read) | delete the arguments |
| `@Tool(examples = ..., version = ..., deprecated = ..., deprecationMessage = ..., tags = ..., timeoutMillis = ...)`, `ToolExample` | deprecation warning `metadata only; not emitted on the wire; removed in 2.0.0` — an **error** under `-Werror` | delete the arguments (they never reached the wire) |

## 4. The `Content` and `ResourceContents` ADTs

### 4.1 `EmbeddedResource.resource` is a sealed ADT

Field access on the embedded payload no longer compiles:

```
value text is not a member of com.tjclp.fastmcp.core.wire.ResourceContents
Not found: text
Found:    Option[String]
Required: String
```

`ResourceContents` exposes only `uri`, `mimeType: Option[String]` and `_meta`; `text` and
`blob` live on the two cases. Match instead of probing (`Text` / `Blob` stand for your own result
type):

```scala
resource match
  case TextResourceContents(uri, text, mimeType, _) => Text(uri, text, mimeType)
  case BlobResourceContents(uri, blob, mimeType, _) => Blob(uri, blob, mimeType)
```

### 4.2 `Content` gained `AudioContent` and `ResourceLink`

An exhaustive match written against the 0.4.0 shape (`TextContent` / `ImageContent` /
`EmbeddedResource`) now warns — and fails under `-Werror`:

```
[warn] match may not be exhaustive.
       It would fail on pattern case: com.tjclp.fastmcp.core.AudioContent(_, _, _, _), com.tjclp.fastmcp.core.ResourceLink(_, _, _, _, _, _, _, _, _)
```

Add the two cases (both are root-exported) or a wildcard fallback. The positional arities of the
existing cases are unchanged: `TextContent(text, _, _)`, `ImageContent(data, mimeType, _, _)`,
`EmbeddedResource(resource, _, _)`.

## 5. Registration semantics

### 5.1 `server.tool(...)` is an effect

`McpServer#tool`, `#prompt` and `#resource` return `ZIO[Any, Throwable, McpServerCore[R]]` (they
have since 0.3.0-rc4). Written as a bare statement the registration is silently discarded and
the server serves **zero** tools (`tools/list` → `-32601`); only `-Wnonunit-statement` flags it:

```
[E176] Potential Issue Warning: unused value of type zio.ZIO[Any, Throwable, com.tjclp.fastmcp.server.McpServerCore[Any]]
```

Sequence it:

```scala
server.tool(addTool) *> server.tool(compileTool) *> server.runHttp()
```

or use `McpServerApp` with `override val tools = List(addTool, compileTool)`.

### 5.2 Duplicate registrations are compile-time errors

Two annotated methods on one object that register the same tool name, prompt name, static URI
or template pattern (templates differing only in placeholder names count as the same pattern)
used to be last-writer-wins with a stderr warning. Now:

```
@Tool name 'add' is registered by 2 annotated methods in P: add(a: Int, b: Int) @ P.scala:9, addAgain(a: Int, b: Int) @ P.scala:11. Each annotated method must register a unique @Tool name — give one of them an explicit name = Some("...") or remove its annotation.
scanAnnotations[P.type]: 1 duplicate registration(s) in P — @Tool name 'add' <- add(a: Int, b: Int) @ P.scala:9 | addAgain(a: Int, b: Int) @ P.scala:11
```

The check is per scanned object; duplicates across objects, or between annotations and typed
contracts on the same server, remain a runtime overwrite with a warning. `scanAnnotations[T]`
also fails at compile time when `T` is not an object's singleton type.

### 5.3 Annotation arguments must be literals; description-only tools keep their method name

`@Tool(name = Some(someVal))` — or any non-literal `description` / `title` / `taskSupport` /
`mimeType` / `schema` / boolean hint — is a compile-time error (`@Tool(name) must be a literal
Option[String] — Some(<literal>), Option(<literal>) or None — but was ...`) instead of being
silently dropped; `final val` constants are accepted. Every literal spelling (`scala.Some("x")`,
`Option("x")`, `new Some("x")`) is honoured now. A `@Tool(description = Some("..."))` without
`name` registers under the **method** name (older releases registered it under the description
text).

### 5.4 Resource template literals are verbatim

`.` in `file://{name}.txt` is a dot, not a regex wildcard. Adjacent placeholders, duplicate
placeholder names, nested or unbalanced braces and `/` inside a placeholder are rejected when
the template is registered, so the server fails to start:

```
IllegalArgumentException: template 'x://{a}{b}' has adjacent placeholders before '{b}' — ambiguous; separate placeholders with literal text
```

Within a segment each separator still binds to its last occurrence: `{name}.{ext}` on
`archive.tar.gz` gives `name = archive.tar`, `ext = gz`, exactly as before. Two templates with
the same placeholder shape (`users://{id}` vs `users://{userId}`) log a warning.

## 6. HTTP transport and settings

- **Default bind is `127.0.0.1`** (since 0.5.0; was `0.0.0.0`). Containers and externally
  reachable deployments set `McpServerSettings(host = "0.0.0.0")` explicitly and pair it with
  `allowedHosts` / `allowedOrigins` behind an authenticating proxy.
- **`stateless` governs only the legacy adapter.** Every MCP 2026-07-28 request is stateless
  and answers on a per-request SSE stream regardless; `stateless = true` selects the JSON-reply
  shape for 2025-11-25 clients. Enabling `tasks` together with `stateless` no longer fails at
  startup; legacy task requests on that adapter answer `-32601` at runtime.
- **Bun: `startStatefulHttp()` / `startStatelessHttp()` return a `BunHttpHandle`** (`port`,
  `hostname`, `url`, `server`, `stop()`; `stop()` also shuts the task manager and the
  idle-session sweeper). They are aliases — the mode comes from `settings.stateless`.
  `BunServeOptions.apply` changed shape and `startBun` is internal.
- **There is no per-request handler to wrap.** 0.x Bun servers that built their own
  `Bun.serve(fetch = ...)` around the SDK transport — adding CORS headers, an `OPTIONS` 204
  preflight, a `/health` route — cannot reproduce that shape: `runHttp()` owns the listener,
  non-endpoint paths answer 404, and the library adds no CORS or preflight handling. The 1.0
  stance is to put those concerns on a reverse proxy in front of the server. A composable
  fetch handler / route hook is tracked for 1.1.0.
- Large `sampling/createMessage` or elicitation results over HTTP meet `maxRequestBodyBytes`
  (1 MiB) before `limits.maxFrameChars`; raise both together (`maxRequestBodyBytes` must stay
  ≤ `maxFrameChars`; checked at startup). See [transports.md](transports.md).

## 7. What your clients will see

Wire-level changes since 0.5.0 / RC3 that a client can observe (JVM and Bun behave the same
unless noted):

| Request | Before | 1.0.0 | Client fix / server knob |
|---|---|---|---|
| POST without `Content-Type: application/json` (missing, empty, `text/plain`, or a non-utf-8 `charset`) — legacy and modern | accepted | `415` `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"Content-Type must be application/json"}}`, before the body is read or a session is minted | send the header |
| POST body over 1 MiB (`Content-Length` or chunked; `initialize` included) | accepted | `413` with an empty body when netty/Bun refuse it first (no session minted); a JSON-RPC `-32000` body on the first-party paths | `McpServerSettings(maxRequestBodyBytes = ...)`, ≤ `limits.maxFrameChars` |
| Frame over 4 MiB of UTF-16 chars; JSON nesting over 64; an object with over 1024 members (any transport) | parsed | `400` `{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error: JSON nesting exceeds limits.maxDepth (64)"}}` / `"Parse error: a JSON object exceeds limits.maxObjectFields (1024)"`; the session survives. stdio: the over-long line is truncated and answered `-32700` | `McpServerSettings(limits = LimitSettings(maxFrameChars = 16 * 1024 * 1024, ...))` |
| `resources/read`, `subscribe`, `unsubscribe` with a URI over 8192 chars | dispatched | `-32602` | `LimitSettings(maxUriChars = ...)` |
| With `allowedHosts` set: a page on another loopback port or scheme (`Origin: http://localhost:3000`), `Origin: null`, an unlisted `Host` | cross-port origin admitted (hostname match) | `403` `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"Host/Origin not allowed (DNS-rebinding protection)"}}` | `McpServerSettings(allowedOrigins = Set("http://localhost:3000"))`; unparseable entries fail `runHttp()` at startup |
| Legacy `Accept: application/json` only, on the stateless adapter | `200` JSON | unchanged: `200` JSON (streamable legacy POSTs must accept `text/event-stream` too; absent `Accept` = accept anything) | none |
| `resources/read` of a URI that matched a template only through the old regex `.` | matched | legacy `-32002` / modern `-32602` with `data.uri` | register a template whose literals match the URIs you issue |
| Two annotated methods with one tool name | last one wins at runtime | the server does not compile (§5.2) | rename one |

## Troubleshooting

**A failed import makes every later error in that file a lie.** Scala 3 turns the unresolved
prefix of a failed wildcard import into an error type, and later lookups in the same file
silently resolve against it. With `import sttp.tapir.generic.auto.*` still present you will see,
on every `@Tool(name = Some("..."), description = Some("..."), readOnlyHint = Some(true))` in that
file:

```
[E171] missing argument for parameter examples of constructor Tool in class Tool: (name: Option[String], description: Option[String], examples: List[String], ...
```

and, on `final case class AddArgs(...) derives JsonDecoder`:

```
type Object in derives clause of AddArgs has no type parameters
```

while a real `Not found: EmbeddedResourceContent` in the same file is not reported at all.
Delete the failed import, recompile, and the genuine errors appear. (The identical `@Tool` line
compiles clean once the import is gone.)

**Scala.js: `compile` is green but `package --js` / `bundle` dies with
`IRVersionNotSupportedException`.** The linker is older than 1.22 — §1.2.

**`Error downloading org.wartremover:wartremover_3.9.0:3.5.6`.** WartRemover is per Scala
version — §1.5.

**`Missing <module>/bun.lock`.** mill-bun 0.3.x freezes installs — §1.3.

**Bun `error: Module not found ".../bunBundle.dest/dist/main.js"`.** The bundle moved to
`bundle.dest` — §1.3.

**`No given instance of type com.tjclp.fastmcp.server.McpServerCoreFactory`.** The import is
missing its `given` selector — §2.1.

**The server starts but `tools/list` is empty.** A registration effect was discarded — §5.1.

**Different zio / zio-json versions in your build.** fast-mcp-scala 1.0.0 is built against
zio 2.1.20 and zio-json 0.7.44. A consumer pinning newer versions (zio 2.1.24, zio-json 0.9.0
were seen) evicts the library's pins; it compiles, but run your test suite before trusting the
eviction at runtime.
