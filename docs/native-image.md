# GraalVM native-image

fast-mcp-scala servers compile to self-contained native binaries with GraalVM `native-image` —
instant startup, no JVM in the deployment container (the motivating case: MCP servers on
Databricks Apps). Tracking issue: [#66](https://github.com/TJC-LP/fast-mcp-scala/issues/66).

| Transport | Status |
|---|---|
| **stdio** (`McpServerApp[Stdio]`, `runStdio()`) | ✅ Supported, CI-gated (`.github/workflows/native.yml`), **zero hand-written reachability metadata** |
| **HTTP** (`McpServerApp[Http]`, `runHttp()`) | ✅ Supported, CI-gated: the official MCP conformance suite runs against the native binary (identical results to the JVM in both `active` and `2026` modes) |

## Why zero metadata works

The library's registration and schema machinery is compile-time (Scala 3 macros, zio-json inline
derivation); the only runtime work is plain ADT transformation (tapir → circe schema rendering),
which uses no reflection. ZIO ships its own native-image metadata in its jar. The
`native-smoke.sh` CI gate is the rot protection — if a future dependency bump introduces a
reflection need, the smoke goes red on the PR that introduces it, and only then does a
`META-INF/native-image/com.tjclp/fast-mcp-scala_3/reachability-metadata.json` entry get added.

## Building a stdio server (downstream recipe, Mill)

```scala
object server extends ScalaModule with mill.javalib.NativeImageModule {
  def scalaVersion = "3.8.3"
  // fast-mcp-scala's annotation macros are @experimental; consumers compile with -experimental
  // (same requirement as the scala-cli quickstart in the README)
  def scalacOptions = Seq("-experimental")
  def mainClass = Some("com.example.MyServer")

  def mvnDeps = Seq(
    // stdio-only: exclude the HTTP stack — see "The stdio/HTTP split" below
    mvn"com.tjclp::fast-mcp-scala:<version>"
      .exclude("dev.zio" -> "zio-http_3")
  )

  // GraalVM provisioned by Mill via the coursier JVM index — no GRAALVM_HOME, no setup-graalvm.
  override def jvmVersion = Task { "graalvm-community:25.0.2" }

  override def nativeImageOptions = Task {
    // Always call super — the default wires resource inclusion and config directories.
    super.nativeImageOptions() ++ Seq("--no-fallback")
  }
}
```

`./mill server.nativeImage` → `out/server/nativeImage.dest/native-executable` (~35 MB for a
small annotated server). Point Claude Desktop (or any MCP client) straight at it:

```json
{ "mcpServers": { "my-server": { "command": "/path/to/native-executable" } } }
```

## The stdio/HTTP split

Since the transport-seam split (TJC-2114), `runStdio()` has no reachable call path into the HTTP
stack: `serveHttp` lives on the separate `HttpTransportBackend` trait, `runHttp()` takes it as a
`using` parameter, and the `TransportRunner[Http]` given is conditional. Closed-world analysis
therefore drops zio-http and netty from stdio-only binaries entirely.

**Stdio-only builds should also exclude the `zio-http` dependency** (as in the recipe above).
This is not just size hygiene: netty's own in-jar reflect-config unconditionally registers
methods whose signatures mention netty buffer types, forcing them reachable, and netty's
`--initialize-at-build-time=io.netty` directive then allocates a native `MemorySegment` in
`EmptyByteBuf.<clinit>` on JDK 25 — failing the build with "Detected a native MemorySegment in
the image heap". With the dependency excluded, none of that metadata is on the image classpath —
and Mill's GraalVM-reachability-metadata-repo integration can stay at its defaults, so any OTHER
dependency you add keeps its repo metadata. (HTTP builds keep netty and counter the stale-repo
problem with a scoped override — see the HTTP section below.)

The in-repo proof: `fast-mcp-scala.nativeSmoke.stdio` builds
`examples.AnnotatedServer` with exactly this shape (it filters netty/zio-http from
`nativeImageClasspath`, which is the moduleDeps equivalent of the dependency exclusion), and
`scripts/native-smoke.sh` asserts both the full MCP handshake and that the binary contains no
`io.netty` / `zio.http` strings.

## Notes and troubleshooting

- **Signal handling**: ZIO's `sun.misc.Signal` hooks are unavailable in a native image; ZIO logs
  a warning and falls back to no-op signal handling (fiber dumps are affected, serving is not).
  For long-running server binaries add `--install-exit-handlers` so SIGINT/SIGTERM terminate the
  process cleanly.
- **`MissingRegistrationError` at runtime**: a dependency started using reflection. Reproduce on
  the JVM under the tracing agent (below), and add only the missing entries.
- **Linker errors on self-hosted runners**: `native-image` needs a C toolchain (`gcc`,
  `zlib1g-dev` on Debian/Ubuntu; preinstalled on GitHub-hosted runners).
- **Build memory**: small servers build in well under 4 GB; pass `-J-Xmx6g` in
  `nativeImageOptions` if the builder OOMs on a constrained runner.

## Regenerating / auditing reachability metadata

The library currently ships **no** metadata because none is needed — verified continuously by
CI. To re-audit (e.g. after a major dependency bump):

1. Build with diagnostics: add `--exact-reachability-metadata` to `nativeImageOptions`, run the
   binary with `-XX:MissingRegistrationReportingMode=Warn`, and exercise it
   (`scripts/native-smoke.sh <binary>`); every near-miss is reported. Treat the output as
   advisory — never bake `--exact-reachability-metadata` into CI (it turns benign try/catch
   `Class.forName` probes inside the JDK into hard errors).
2. If something genuinely needs registration, capture it with the agent, driving the JVM with
   the same smoke requests:

   ```bash
   GRAAL_JAVA="$(./mill show fast-mcp-scala.nativeSmoke.stdio.javaHome | ... )/bin/java"
   CP="$(./mill show fast-mcp-scala.jvm.runClasspath | ...)"
   NATIVE_SMOKE_CMD="$GRAAL_JAVA -agentlib:native-image-agent=config-output-dir=/tmp/ni-config \
     -cp '$CP' com.tjclp.fastmcp.examples.AnnotatedServer" ./scripts/native-smoke.sh
   ```

3. Hand-distill the delta (never wholesale-copy the agent output — it over-captures) into
   `fast-mcp-scala/jvm/resources/META-INF/native-image/com.tjclp/fast-mcp-scala_3/reachability-metadata.json`
   (the modern single-file format). Mill's default `resources` ships it inside the published jar,
   so downstream builds stay zero-config.

## Building an HTTP server

HTTP native images keep zio-http/netty and need exactly two extra flags:

```scala
object server extends ScalaModule with mill.javalib.NativeImageModule {
  def scalaVersion = "3.8.3"
  def mainClass = Some("com.example.MyHttpServer")
  def mvnDeps = Seq(mvn"com.tjclp::fast-mcp-scala:<version>")   // zio-http stays

  override def jvmVersion = Task { "graalvm-community:25.0.2" }
  override def nativeMetadataConfigurations =
    Task { Set.empty[mill.javalib.graalvm.MetadataResult] }
  override def nativeExcludedConfigJars = Task { Seq.empty[PathRef] }

  override def nativeImageOptions = Task {
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      // SIGINT/SIGTERM must terminate a long-running server binary.
      "--install-exit-handlers",
      // netty-codec-http ships a blanket `--initialize-at-build-time=io.netty`, which is
      // incompatible with GraalVM on JDK 25 (buffer/handler <clinit>s allocate native memory via
      // the FFM CleanerJava25 and bake response objects into the image heap). Equal specificity +
      // later rule wins, so this re-defaults the whole netty tree to run-time init; netty's own
      // class-specific run-time entries are unaffected.
      "--initialize-at-run-time=io.netty"
    )
  }
}
```

Runtime behavior baked into `JvmHttpBackend`:

- **Netty channel type**: `AUTO` (epoll/kqueue) on a normal JVM, **`NIO` inside a native image**
  (detected via the `org.graalvm.nativeimage.imagecode` property — `AUTO`'s runtime transport
  probing is exactly what closed-world analysis can't tolerate). Override with
  `-Dfastmcp.http.channelType=nio|epoll|kqueue|auto` if you know what you're doing.
- **Container binding**: the default host is `127.0.0.1`; set
  `McpServerSettings(host = "0.0.0.0")` for containerized deployments (Databricks Apps included).

The in-repo proof: `fast-mcp-scala.nativeSmoke.http` builds the conformance server, and
`scripts/conformance.sh native [port] [active|2026]` runs the **official MCP conformance suite**
against the native binary, held to the unchanged (empty) JVM baseline — measured identical to the
JVM in both modes. CI runs both on every PR.
