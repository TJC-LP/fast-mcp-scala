package com.tjclp.fastmcp
package server.transport

import java.nio.file.{Files, Path as NioPath, Paths}
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.*

import com.tjclp.fastmcp.examples.{HttpServer, SkillsHttpServer}
import com.tjclp.fastmcp.examples.bench.BenchServer
import com.tjclp.fastmcp.examples.conformance.ConformanceServer
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.http.StreamableHttpHandler

/** Host / Origin validation is ON for a server started with default settings on a loopback bind
  * address: nobody has to find `allowedHosts` to get it. Requests go through the real zio-http
  * routes in memory (the gates are the shared `HttpRequestGuards`, so the socket backend, Scala
  * Native and Bun take the same verdicts; `SocketHttpBackendTest` and `JsServerHttpTest` pin them
  * on the wire).
  */
class DefaultHostValidationTest extends AnyFunSuite with Matchers:

  private val listFrame = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""

  private val JsonHeaders = List(
    "content-type" -> "application/json",
    "accept" -> "application/json, text/event-stream"
  )

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private def routesFor(settings: McpServerSettings): Routes[Any, Response] =
    val server = McpServer.typed[Any]("T", "0.1.0", settings)
    runUnsafe(
      server.buildRouter.flatMap(r => JvmHttpBackend.httpRoutes(r, settings, ZEnvironment.empty))
    )

  private def send(routes: Routes[Any, Response], base: Request, headers: (String, String)*): Int =
    val req = headers.foldLeft(base) { case (r, (k, v)) => r.addHeader(Header.Custom(k, v)) }
    runUnsafe(ZIO.scoped(routes.runZIO(req))).status.code

  private def post(routes: Routes[Any, Response], headers: (String, String)*): Int =
    send(
      routes,
      Request.post(URL(Path.root / "mcp"), Body.fromString(listFrame)),
      (headers.toList ++ JsonHeaders)*
    )

  /** Legacy stateless mode: a `tools/list` POST answers 200 with no handshake. */
  private val defaults = McpServerSettings(stateless = true)

  test("default settings bind loopback (the premise of the automatic guard)") {
    McpServerSettings().host shouldBe "127.0.0.1"
    McpServerSettings().allowedHosts shouldBe None
    McpServerSettings().allowedOrigins shouldBe None
  }

  test("default settings: a POST whose Host is not a loopback name is 403") {
    val routes = routesFor(defaults)
    for host <- List(
        "evil.example.com",
        "evil.example.com:8000",
        "192.168.1.20:8000",
        "localhost.evil.example.com:8000",
        "127.0.0.1.evil.example.com"
      )
    do withClue(s"Host: $host")(post(routes, "host" -> host) shouldBe 403)
  }

  test("default settings: loopback Hosts on any port pass, as does an absent Host") {
    val routes = routesFor(defaults)
    for host <- List(
        "127.0.0.1:8000",
        "127.0.0.1",
        "localhost:8000",
        "LOCALHOST:8000",
        "localhost:9000", // a forwarded port (ssh -L, an IDE port-forward) is still loopback
        "[::1]:8000"
      )
    do withClue(s"Host: $host")(post(routes, "host" -> host) shouldBe 200)
    post(routes) shouldBe 200
  }

  test("default settings: a present Origin must be same-origin with the request's Host") {
    val routes = routesFor(defaults)
    val host = "host" -> "localhost:8000"
    for origin <- List(
        "http://evil.example.com",
        "http://evil.example.com:8000",
        "https://evil.example.com",
        "http://localhost:3000", // another local page, on another port
        "http://127.0.0.1:8000", // loopback, but not this request's authority
        "null",
        ""
      )
    do withClue(s"Origin: $origin")(post(routes, host, "origin" -> origin) shouldBe 403)
    post(routes, host, "origin" -> "http://localhost:8000") shouldBe 200
    // A foreign Origin is refused even when no Host is sent.
    post(routes, "origin" -> "http://evil.example.com") shouldBe 403
  }

  test("default settings: GET and DELETE are guarded like POST") {
    val routes = routesFor(McpServerSettings())
    val evil = List("host" -> "evil.example.com", "mcp-session-id" -> "whatever")
    send(routes, Request.get(URL(Path.root / "mcp")), evil*) shouldBe 403
    send(routes, Request.delete(URL(Path.root / "mcp")), evil*) shouldBe 403
    val foreignOrigin = List(
      "host" -> "localhost:8000",
      "origin" -> "http://evil.example.com",
      "mcp-session-id" -> "whatever"
    )
    send(routes, Request.get(URL(Path.root / "mcp")), foreignOrigin*) shouldBe 403
    send(routes, Request.delete(URL(Path.root / "mcp")), foreignOrigin*) shouldBe 403
    // A loopback Host reaches the session lookup (unknown session → 404), not the guard.
    val local = List("host" -> "localhost:8000", "mcp-session-id" -> "whatever")
    send(routes, Request.delete(URL(Path.root / "mcp")), local*) shouldBe 404
  }

  test("default settings on other loopback bind addresses are guarded too") {
    for bind <- List("localhost", "::1", "[::1]", "127.0.0.2", "0:0:0:0:0:0:0:1") do
      withClue(s"host = $bind") {
        val routes = routesFor(defaults.copy(host = bind))
        post(routes, "host" -> "evil.example.com") shouldBe 403
        post(routes, "host" -> "localhost:8000", "origin" -> "http://evil.example.com") shouldBe 403
        post(routes, "host" -> "localhost:8000") shouldBe 200
      }
    // The bound literal itself is a loopback name for that listener.
    val on2 = routesFor(defaults.copy(host = "127.0.0.2"))
    post(on2, "host" -> "127.0.0.2:8000") shouldBe 200
    post(on2, "host" -> "127.0.0.2:8000", "origin" -> "http://127.0.0.2:8000") shouldBe 200
    // ...and a 127/8 address this listener is not bound to is not.
    post(routesFor(defaults), "host" -> "127.0.0.2:8000") shouldBe 403
  }

  // ---- the explicit opt-out, and the documented alternative ----

  test("dnsRebindingProtection = false turns the check off: any Host and Origin is served") {
    val routes = routesFor(defaults.copy(dnsRebindingProtection = false))
    post(routes, "host" -> "evil.example.com") shouldBe 200
    post(routes, "host" -> "mcp.example.com", "origin" -> "https://app.example.com") shouldBe 200
    post(routes, "host" -> "localhost:8000", "origin" -> "http://localhost:3000") shouldBe 200
  }

  test("a loopback listener behind a reverse proxy: list the proxied Host instead of opting out") {
    val routes = routesFor(defaults.copy(allowedHosts = Some(Set("mcp.example.com", "localhost"))))
    post(routes, "host" -> "mcp.example.com") shouldBe 200
    post(routes, "host" -> "mcp.example.com", "origin" -> "https://mcp.example.com") shouldBe 200
    post(routes, "host" -> "localhost:8000") shouldBe 200
    post(routes, "host" -> "evil.example.com") shouldBe 403
    post(routes, "host" -> "mcp.example.com", "origin" -> "https://evil.example.com") shouldBe 403
  }

  test("a non-loopback bind with nothing listed serves any Host (and warns at startup)") {
    val routes = routesFor(defaults.copy(host = "0.0.0.0"))
    post(routes, "host" -> "mcp.example.com") shouldBe 200
    post(routes, "host" -> "evil.example.com", "origin" -> "http://evil.example.com") shouldBe 200
  }

  test("startup refuses the opt-out combined with an allow-list") {
    val failure = runUnsafe(
      StreamableHttpHandler
        .validateSettings(
          McpServerSettings(allowedHosts = Some(Set("localhost")), dnsRebindingProtection = false)
        )
        .either
    )
    failure.isLeft shouldBe true
    failure.swap.map(_.getMessage).getOrElse("") should include("dnsRebindingProtection")
  }

  // ---- shipped HTTP examples ----

  private val exampleSettings: List[(String, McpServerSettings)] = List(
    "HttpServer" -> HttpServer.settings,
    "SkillsHttpServer" -> SkillsHttpServer.settings,
    "ConformanceServer" -> ConformanceServer.settings(ConformanceServer.DefaultPort),
    "BenchServer (streamable)" -> BenchServer.settings(BenchServer.DefaultPort),
    "BenchServer (stateless)" -> BenchServer.settings(BenchServer.DefaultPort, stateless = true)
  )

  test("every shipped JVM HTTP example answers 403 to a non-loopback Host and a foreign Origin") {
    for (name, settings) <- exampleSettings do
      withClue(s"$name: ") {
        val routes = routesFor(settings)
        val port = settings.port
        post(routes, "host" -> "evil.example.com") shouldBe 403
        post(routes, "host" -> s"evil.example.com:$port") shouldBe 403
        post(
          routes,
          "host" -> s"localhost:$port",
          "origin" -> "http://evil.example.com"
        ) shouldBe 403
        post(
          routes,
          "host" -> s"localhost:$port",
          "origin" -> s"http://evil.example.com:$port"
        ) shouldBe 403
        // A loopback request is not refused by the guard (whatever the handler then answers).
        post(routes, "host" -> s"127.0.0.1:$port") should not be 403
        post(routes, "host" -> s"localhost:$port") should not be 403
      }
  }

  test("ConformanceServer keeps its explicit allow-list (the DNS-rebinding scenario's fixture)") {
    val settings = ConformanceServer.settings(ConformanceServer.DefaultPort)
    settings.host shouldBe "127.0.0.1"
    settings.allowedHosts shouldBe Some(Set("127.0.0.1", "localhost", "[::1]"))
    settings.allowedOrigins shouldBe None
  }

  // ---- the Scala.js example and the user-facing documents (source scans) ----

  private def repoRoot: NioPath =
    Iterator
      .iterate(Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.exists(p.resolve("build.mill")) && Files.isDirectory(p.resolve("fast-mcp-scala"))
      )
      .getOrElse(fail("could not locate the repository root (build.mill) from user.dir"))

  /** A file's text with every run of whitespace (line wraps included) folded to one space. */
  private def textOf(relative: String): String =
    Files.readString(repoRoot.resolve(relative)).replaceAll("\\s+", " ")

  /** `text` contains `phrase` — without dumping the whole document into the failure message. */
  private def mentions(text: String, phrase: String): Unit =
    withClue(s"expected to find: $phrase — ")(text.contains(phrase) shouldBe true)

  test("HttpServerJs enables the guard and no longer presents loopback binding as a substitute") {
    // The Scala.js example cannot be loaded on the JVM; `JsDefaultHostValidationTest` drives it on
    // Bun. Here: its settings list the loopback names, and the misleading comment is gone.
    val source =
      textOf("fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HttpServerJs.scala")
    mentions(source, """allowedHosts = Some(Set("localhost", "127.0.0.1", "[::1]"))""")
    mentions(source, "keeps a web page in a local browser out")
    withClue("the old comment is still there: ") {
      source.contains("unless you set allowedHosts") shouldBe false
    }
  }

  test("no shipped example opts out of the guard or binds a non-loopback address") {
    val root = repoRoot.resolve("fast-mcp-scala")
    val sources = List("shared", "jvm", "js", "native", "jvm-native")
      .map(platform => root.resolve(platform).resolve("src/com/tjclp/fastmcp/examples"))
      .filter(Files.isDirectory(_))
      .flatMap(dir => Files.walk(dir).iterator().asScala.filter(_.toString.endsWith(".scala")))
    sources should not be empty
    val offenders = sources.flatMap { file =>
      Files.readAllLines(file).asScala.zipWithIndex.collect {
        case (line, idx)
            if line.contains("dnsRebindingProtection = false") ||
              line.matches(""".*\bhost\s*=\s*"(0\.0\.0\.0|::|\[::\])".*""") &&
              !line.trim.startsWith("*") && !line.trim.startsWith("//") =>
          s"$file:${idx + 1}: ${line.trim}"
      }
    }
    withClue(offenders.mkString("\n"))(offenders shouldBe empty)
  }

  // ---- startup warning on a non-loopback bind without an allow-list ----

  /** Run the startup validation every JVM / Native backend runs, capturing what it logs. */
  private def startupLogs(settings: McpServerSettings): List[(LogLevel, String)] =
    val seen = new ConcurrentLinkedQueue[(LogLevel, String)]()
    val capture = new ZLogger[String, Unit]:
      def apply(
          trace: Trace,
          fiberId: FiberId,
          logLevel: LogLevel,
          message: () => String,
          cause: Cause[Any],
          context: FiberRefs,
          spans: List[LogSpan],
          annotations: Map[String, String]
      ): Unit =
        val _ = seen.add((logLevel, message()))
    runUnsafe(
      StreamableHttpHandler.validateSettings(settings).provideLayer(Runtime.addLogger(capture))
    )
    seen.asScala.toList

  private def warnings(settings: McpServerSettings): List[String] =
    startupLogs(settings).collect { case (LogLevel.Warning, msg) => msg }

  test("startup warns loudly when a non-loopback bind has no allow-list") {
    for bind <- List("0.0.0.0", "::", "[::]", "192.168.1.20", "mcp.internal.example.com") do
      withClue(s"host = $bind") {
        val logged = warnings(McpServerSettings(host = bind))
        logged should have size 1
        logged.head should include(bind)
        logged.head should include("allowedHosts")
        logged.head should include("allowedOrigins")
      }
  }

  test("startup does not warn on a loopback bind or when an allow-list is set") {
    warnings(McpServerSettings()) shouldBe Nil
    warnings(McpServerSettings(host = "localhost")) shouldBe Nil
    warnings(McpServerSettings(host = "::1")) shouldBe Nil
    warnings(
      McpServerSettings(host = "0.0.0.0", allowedHosts = Some(Set("mcp.example.com")))
    ) shouldBe Nil
    warnings(
      McpServerSettings(host = "0.0.0.0", allowedOrigins = Some(Set("https://app.example.com")))
    ) shouldBe Nil
    // The opt-out is the operator's acknowledgement: nothing left to warn about.
    warnings(McpServerSettings(host = "0.0.0.0", dnsRebindingProtection = false)) shouldBe Nil
  }
