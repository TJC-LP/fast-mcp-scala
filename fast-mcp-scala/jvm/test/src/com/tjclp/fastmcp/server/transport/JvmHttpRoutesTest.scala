package com.tjclp.fastmcp
package server.transport

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.http.{Server as ZServer, *}

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmHttpBackend.given
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** `McpServerSettings.httpRoutes` (GH #120) as the zio-http backend renders it: in memory through
  * its `Routes`, and on a real port through netty. The decisions are the shared handler's
  * (`HttpRouteHandlerTest`); this suite pins the rendering and that `runHttp()` refuses bad routes.
  */
class JvmHttpRoutesTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val routes = List(
    HttpRoute.get("/health")(_ => ZIO.succeed(HttpRouteResponse.text("ok"))),
    HttpRoute.get("/ready") { req =>
      ZIO.succeed(
        HttpRouteResponse
          .json(s"""{"probe":"${req.header("X-Probe").getOrElse("")}"}""", status = 503)
          .withHeader("Retry-After", "5")
      )
    },
    HttpRoute.get("/broken")(_ => ZIO.die(new RuntimeException("secret-cause")))
  )

  private def server(settings: McpServerSettings) = McpServer.typed[Any]("R", "0.1.0", settings)

  private def buildRoutes(settings: McpServerSettings): Routes[Any, Response] =
    val s = server(settings)
    runUnsafe(
      s.buildRouter.flatMap(r => JvmHttpBackend.httpRoutes(r, s.settings, ZEnvironment.empty))
    )

  private def run(app: Routes[Any, Response], req: Request): Response =
    runUnsafe(ZIO.scoped(app.runZIO(req)))

  private def get(path: String, headers: (String, String)*): Request =
    headers.foldLeft(Request.get(URL(Path.decode(path)))) { case (req, (name, value)) =>
      req.addHeader(Header.Custom(name, value))
    }

  private def bodyOf(response: Response): String = runUnsafe(response.body.asString)

  test("zio-http: a GET on a route answers with its status, content type, headers and body") {
    val app = buildRoutes(McpServerSettings(httpRoutes = routes))
    val health = run(app, get("/health"))
    health.status shouldBe Status.Ok
    health.rawHeader("content-type") shouldBe Some("text/plain; charset=utf-8")
    bodyOf(health) shouldBe "ok"

    val ready = run(app, get("/ready", "X-Probe" -> "kubelet"))
    ready.status shouldBe Status.ServiceUnavailable
    ready.rawHeader("content-type") shouldBe Some("application/json")
    ready.rawHeader("retry-after") shouldBe Some("5")
    bodyOf(ready) shouldBe """{"probe":"kubelet"}"""

    val broken = run(app, get("/broken"))
    broken.status shouldBe Status.InternalServerError
    bodyOf(broken) should include("Internal server error")
    bodyOf(broken) should not include "secret"
  }

  test("zio-http: Host guard 403 first, 405 with Allow: GET for other methods, 404 elsewhere") {
    val app = buildRoutes(McpServerSettings(httpRoutes = routes))
    run(app, get("/health", "Host" -> "evil.example")).status shouldBe Status.Forbidden
    run(app, get("/health", "Host" -> "localhost:8000", "Origin" -> "http://evil.example")).status shouldBe
      Status.Forbidden
    run(app, get("/health", "Host" -> "localhost:8000")).status shouldBe Status.Ok
    for method <- List(Method.POST, Method.PUT, Method.DELETE, Method.OPTIONS, Method.HEAD) do
      withClue(s"$method: ") {
        val refused = run(app, get("/health").copy(method = method))
        refused.status shouldBe Status.MethodNotAllowed
        refused.rawHeader("allow") shouldBe Some("GET")
        run(app, get("/health", "Host" -> "evil.example").copy(method = method)).status shouldBe
          Status.Forbidden
      }
    for path <- List("/health/", "/HEALTH", "/other") do
      withClue(s"$path: ") {
        val missing = run(app, get(path))
        missing.status shouldBe Status.NotFound
        bodyOf(missing) shouldBe ""
      }
    // Without routes the same path is the 404 it always was.
    run(buildRoutes(McpServerSettings()), get("/health")).status shouldBe Status.NotFound
  }

  test("zio-http: the MCP endpoint answers as before next to routes") {
    val app = buildRoutes(McpServerSettings(httpRoutes = routes))
    val init = run(
      app,
      Request
        .post(URL(Path.root / "mcp"), Body.fromString(initFrame))
        .addHeader(Header.Custom("content-type", "application/json"))
        .addHeader(Header.Custom("accept", "application/json, text/event-stream"))
    )
    init.status shouldBe Status.Ok
    init.rawHeader("mcp-session-id").isDefined shouldBe true
    // A GET on the endpoint is the session adapter's, never a route's.
    run(app, get("/mcp")).status shouldBe Status.BadRequest
  }

  test("runHttp refuses to start with a route on the MCP endpoint") {
    val bad = McpServerSettings(
      port = 0,
      httpRoutes = List(HttpRoute.get("/mcp")(_ => ZIO.succeed(HttpRouteResponse.text("x"))))
    )
    val failure = runUnsafe(server(bad).runHttp().either)
    failure.left.map(_.getMessage).left.getOrElse("started") should include("is the MCP endpoint")
  }

  // --- on a real port ---------------------------------------------------------------------------

  private def readLine(in: InputStream): String =
    val buf = new ByteArrayOutputStream()
    var byte = in.read()
    while byte != -1 && byte != '\n' do
      buf.write(byte)
      byte = in.read()
    buf.toString("ISO-8859-1").stripSuffix("\r")

  private def readReply(in: InputStream): (Int, Map[String, String], String) =
    val status = readLine(in).split(' ')(1).toInt
    val headers = Iterator
      .continually(readLine(in))
      .takeWhile(_.nonEmpty)
      .map { line =>
        val i = line.indexOf(':')
        line.substring(0, i).trim.toLowerCase -> line.substring(i + 1).trim
      }
      .toMap
    val length = headers.get("content-length").map(_.toInt).getOrElse(0)
    (status, headers, new String(in.readNBytes(length), StandardCharsets.UTF_8))

  test("zio-http on a real port: routes and the endpoint share one keep-alive connection") {
    val s = server(McpServerSettings(httpRoutes = routes))
    runUnsafe(
      ZIO
        .scoped {
          for
            router <- s.buildRouter
            app <- JvmHttpBackend.httpRoutes(router, s.settings, ZEnvironment.empty)
            port <- ZServer.install(app)
            _ <- ZIO.attemptBlocking {
              val socket = new Socket()
              try
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000)
                socket.setSoTimeout(10_000)
                val out = socket.getOutputStream
                val in = new BufferedInputStream(socket.getInputStream)
                def send(raw: String): Unit =
                  out.write(raw.getBytes(StandardCharsets.UTF_8))
                  out.flush()

                send(s"GET /health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
                val (status, headers, body) = readReply(in)
                status shouldBe 200
                headers.get("content-type") shouldBe Some("text/plain; charset=utf-8")
                headers.get("content-length") shouldBe Some("2")
                body shouldBe "ok"

                send(
                  s"POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n" +
                    "Accept: application/json, text/event-stream\r\n" +
                    s"Content-Length: ${initFrame.length}\r\n\r\n$initFrame"
                )
                readReply(in)._1 shouldBe 200

                send(s"GET /ready?x=1 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-Probe: lb\r\n\r\n")
                val (readyStatus, readyHeaders, readyBody) = readReply(in)
                readyStatus shouldBe 503
                readyHeaders.get("retry-after") shouldBe Some("5")
                readyBody shouldBe """{"probe":"lb"}"""

                send(s"GET /health HTTP/1.1\r\nHost: evil.example\r\n\r\n")
                readReply(in)._1 shouldBe 403
              finally socket.close()
            }
          yield ()
        }
        .provide(ZServer.defaultWithPort(0))
        .timeoutFail(new RuntimeException("zio-http route wire test timed out"))(60.seconds)
    )
  }
